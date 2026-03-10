package org.embeddedt.blacksmith.impl.transformers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.instrument.IllegalClassFormatException;
import java.util.Collections;
import java.util.List;

/**
 * Defers {@code ListenerListInst} allocation in {@code ListenerList} until a listener is actually registered.
 *
 * <p>In large modpacks (300+ event buses, ~500 event classes), {@code resizeLists} eagerly creates a
 * {@code ListenerListInst} for every bus ID on every {@code ListenerList}, producing ~150k instances (~40 MB)
 * where the vast majority have zero listeners.</p>
 *
 * <p>This transformer makes the following changes to {@code ListenerList}:</p>
 * <ul>
 *   <li>Adds a static {@code EMPTY_LISTENERS} field (empty {@code IEventListener[]}) as a fallback return value.</li>
 *   <li>Adds a {@code getOrCreateInstance(int)} method that lazily creates a {@code ListenerListInst} on demand,
 *       recursively ensuring parent instances are created first (child-to-parent lock order).</li>
 *   <li>Replaces {@code resizeLists} to grow the array without filling new slots — they stay {@code null}.</li>
 *   <li>Replaces {@code getListeners} to null-check the slot and delegate to the parent when absent.</li>
 *   <li>Replaces both {@code register} overloads to call {@code getOrCreateInstance} before registering.</li>
 *   <li>Replaces {@code unregister} and {@code clearBusID} to null-check before operating on the slot.</li>
 * </ul>
 */
public class ListenerListLazyAllocTransformer implements RuntimeTransformer, Opcodes {
    private static final String OWNER     = "net/minecraftforge/eventbus/ListenerList";
    private static final String INST      = "net/minecraftforge/eventbus/ListenerList$ListenerListInst";
    private static final String INST_DESC = "L" + INST + ";";
    private static final String INST_ARR  = "[L" + INST + ";";
    private static final String ILISTENER = "net/minecraftforge/eventbus/api/IEventListener";
    private static final String IL_ARR    = "[L" + ILISTENER + ";";
    private static final String PRIORITY  = "net/minecraftforge/eventbus/api/EventPriority";
    private static final String BUS       = "net/minecraftforge/eventbus/EventBus";

    @Override
    public List<String> getTransformedClasses() {
        return Collections.singletonList(OWNER);
    }

    @Override
    public int getWriteFlags() {
        return ClassWriter.COMPUTE_FRAMES;
    }

    @Override
    public void transformClass(ClassNode data) throws IllegalClassFormatException {
        addEmptyListenersField(data);
        addGetOrCreateInstanceMethod(data);

        for (MethodNode method : data.methods) {
            switch (method.name) {
                case "resizeLists":
                    if (method.desc.equals("(I)V")) replaceResizeLists(method);
                    break;
                case "getListeners":
                    if (method.desc.equals("(I)" + IL_ARR)) replaceGetListeners(method);
                    break;
                case "register":
                    if (method.desc.equals("(IL" + PRIORITY + ";L" + ILISTENER + ";)V"))
                        replaceRegister3(method);
                    else if (method.desc.equals("(IL" + BUS + ";L" + PRIORITY + ";L" + ILISTENER + ";)V"))
                        replaceRegister4(method);
                    break;
                case "unregister":
                    if (method.desc.equals("(IL" + ILISTENER + ";)V")) replaceUnregister(method);
                    break;
                case "clearBusID":
                    if (method.desc.equals("(I)V")) replaceClearBusID(method);
                    break;
            }
        }
    }

    private void addEmptyListenersField(ClassNode data) {
        // Add field: private static final IEventListener[] EMPTY_LISTENERS
        data.fields.add(new FieldNode(
                ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
                "EMPTY_LISTENERS", IL_ARR, null, null));

        // Initialize in <clinit>: EMPTY_LISTENERS = new IEventListener[0]
        for (MethodNode method : data.methods) {
            if (method.name.equals("<clinit>")) {
                // Insert before the first RETURN
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == RETURN) {
                        InsnList init = new InsnList();
                        init.add(new InsnNode(ICONST_0));
                        init.add(new TypeInsnNode(ANEWARRAY, ILISTENER));
                        init.add(new FieldInsnNode(PUTSTATIC, OWNER, "EMPTY_LISTENERS", IL_ARR));
                        method.instructions.insertBefore(insn, init);
                        break;
                    }
                }
                break;
            }
        }
    }

    private void addGetOrCreateInstanceMethod(ClassNode data) {
        // private synchronized ListenerListInst getOrCreateInstance(int id)
        MethodNode m = new MethodNode(ACC_PRIVATE | ACC_SYNCHRONIZED,
                "getOrCreateInstance", "(I)" + INST_DESC, null, null);

        InsnList il = m.instructions;

        // ListenerListInst inst = this.lists[id];
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "lists", INST_ARR));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new InsnNode(AALOAD));
        il.add(new VarInsnNode(ASTORE, 2)); // inst

        // if (inst != null) return inst;
        Label createInst = new Label();
        LabelNode createInstLabel = new LabelNode(createInst);
        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new JumpInsnNode(IFNULL, createInstLabel));
        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new InsnNode(ARETURN));

        // Create instance
        il.add(createInstLabel);

        // if (this.parent != null)
        Label noParent = new Label();
        LabelNode noParentLabel = new LabelNode(noParent);
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "parent", "L" + OWNER + ";"));
        il.add(new JumpInsnNode(IFNULL, noParentLabel));

        // inst = new ListenerListInst(this.parent.getOrCreateInstance(id));
        il.add(new TypeInsnNode(NEW, INST));
        il.add(new InsnNode(DUP));
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "parent", "L" + OWNER + ";"));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, OWNER, "getOrCreateInstance", "(I)" + INST_DESC, false));
        il.add(new MethodInsnNode(INVOKESPECIAL, INST, "<init>", "(" + INST_DESC + ")V", false));
        il.add(new VarInsnNode(ASTORE, 2));
        Label storeAndReturn = new Label();
        LabelNode storeAndReturnLabel = new LabelNode(storeAndReturn);
        il.add(new JumpInsnNode(GOTO, storeAndReturnLabel));

        // else: inst = new ListenerListInst();
        il.add(noParentLabel);
        il.add(new TypeInsnNode(NEW, INST));
        il.add(new InsnNode(DUP));
        il.add(new MethodInsnNode(INVOKESPECIAL, INST, "<init>", "()V", false));
        il.add(new VarInsnNode(ASTORE, 2));

        // this.lists[id] = inst;
        il.add(storeAndReturnLabel);
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "lists", INST_ARR));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new InsnNode(AASTORE));

        // return inst;
        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new InsnNode(ARETURN));

        data.methods.add(m);
    }

    private void clearMethod(MethodNode method) {
        method.instructions.clear();
        method.localVariables.clear();
        method.tryCatchBlocks.clear();
    }

    private void replaceResizeLists(MethodNode method) {
        clearMethod(method);
        InsnList il = method.instructions;

        // if (parent != null) parent.resizeLists(max);
        Label noParent = new Label();
        LabelNode noParentLabel = new LabelNode(noParent);
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "parent", "L" + OWNER + ";"));
        il.add(new JumpInsnNode(IFNULL, noParentLabel));
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "parent", "L" + OWNER + ";"));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new MethodInsnNode(INVOKESPECIAL, OWNER, "resizeLists", "(I)V", false));
        il.add(noParentLabel);

        // if (lists.length >= max) return;
        Label doResize = new Label();
        LabelNode doResizeLabel = new LabelNode(doResize);
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "lists", INST_ARR));
        il.add(new InsnNode(ARRAYLENGTH));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new JumpInsnNode(IF_ICMPLT, doResizeLabel));
        il.add(new InsnNode(RETURN));

        // ListenerListInst[] newList = new ListenerListInst[max];
        il.add(doResizeLabel);
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new TypeInsnNode(ANEWARRAY, INST));
        il.add(new VarInsnNode(ASTORE, 2)); // newList

        // System.arraycopy(lists, 0, newList, 0, lists.length);
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "lists", INST_ARR));
        il.add(new InsnNode(ICONST_0));
        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new InsnNode(ICONST_0));
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "lists", INST_ARR));
        il.add(new InsnNode(ARRAYLENGTH));
        il.add(new MethodInsnNode(INVOKESTATIC, "java/lang/System", "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V", false));

        // lists = newList;
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new FieldInsnNode(PUTFIELD, OWNER, "lists", INST_ARR));

        il.add(new InsnNode(RETURN));
    }

    private void replaceGetListeners(MethodNode method) {
        clearMethod(method);
        InsnList il = method.instructions;

        // ListenerListInst inst = lists[id];
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "lists", INST_ARR));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new InsnNode(AALOAD));
        il.add(new VarInsnNode(ASTORE, 2)); // inst

        // if (inst != null) return inst.getListeners();
        Label instNull = new Label();
        LabelNode instNullLabel = new LabelNode(instNull);
        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new JumpInsnNode(IFNULL, instNullLabel));
        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, INST, "getListeners", "()" + IL_ARR, false));
        il.add(new InsnNode(ARETURN));

        il.add(instNullLabel);

        // if (parent != null) return parent.getListeners(id);
        Label noParent = new Label();
        LabelNode noParentLabel = new LabelNode(noParent);
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "parent", "L" + OWNER + ";"));
        il.add(new JumpInsnNode(IFNULL, noParentLabel));
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "parent", "L" + OWNER + ";"));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, OWNER, "getListeners", "(I)" + IL_ARR, false));
        il.add(new InsnNode(ARETURN));

        // return EMPTY_LISTENERS;
        il.add(noParentLabel);
        il.add(new FieldInsnNode(GETSTATIC, OWNER, "EMPTY_LISTENERS", IL_ARR));
        il.add(new InsnNode(ARETURN));
    }

    private void replaceRegister3(MethodNode method) {
        // register(int id, EventPriority priority, IEventListener listener)
        clearMethod(method);
        InsnList il = method.instructions;

        // getOrCreateInstance(id).register(priority, listener);
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, OWNER, "getOrCreateInstance", "(I)" + INST_DESC, false));
        il.add(new VarInsnNode(ALOAD, 2)); // priority
        il.add(new VarInsnNode(ALOAD, 3)); // listener
        il.add(new MethodInsnNode(INVOKEVIRTUAL, INST, "register",
                "(L" + PRIORITY + ";L" + ILISTENER + ";)V", false));
        il.add(new InsnNode(RETURN));
    }

    private void replaceRegister4(MethodNode method) {
        // register(int id, EventBus eventBus, EventPriority priority, IEventListener listener)
        clearMethod(method);
        InsnList il = method.instructions;

        // var list = getOrCreateInstance(id);
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, OWNER, "getOrCreateInstance", "(I)" + INST_DESC, false));
        il.add(new VarInsnNode(ASTORE, 5)); // list

        // list.phasesToTrack = eventBus.phasesToTrack;
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new VarInsnNode(ALOAD, 2)); // eventBus
        il.add(new FieldInsnNode(GETFIELD, BUS, "phasesToTrack", "Ljava/util/EnumSet;"));
        il.add(new FieldInsnNode(PUTFIELD, INST, "phasesToTrack", "Ljava/util/EnumSet;"));

        // list.register(priority, listener);
        il.add(new VarInsnNode(ALOAD, 5));
        il.add(new VarInsnNode(ALOAD, 3)); // priority
        il.add(new VarInsnNode(ALOAD, 4)); // listener
        il.add(new MethodInsnNode(INVOKEVIRTUAL, INST, "register",
                "(L" + PRIORITY + ";L" + ILISTENER + ";)V", false));
        il.add(new InsnNode(RETURN));
    }

    private void replaceUnregister(MethodNode method) {
        clearMethod(method);
        InsnList il = method.instructions;

        // ListenerListInst inst = lists[id];
        il.add(new VarInsnNode(ALOAD, 0));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "lists", INST_ARR));
        il.add(new VarInsnNode(ILOAD, 1));
        il.add(new InsnNode(AALOAD));
        il.add(new VarInsnNode(ASTORE, 3)); // inst

        // if (inst != null) inst.unregister(listener);
        Label end = new Label();
        LabelNode endLabel = new LabelNode(end);
        il.add(new VarInsnNode(ALOAD, 3));
        il.add(new JumpInsnNode(IFNULL, endLabel));
        il.add(new VarInsnNode(ALOAD, 3));
        il.add(new VarInsnNode(ALOAD, 2)); // listener
        il.add(new MethodInsnNode(INVOKEVIRTUAL, INST, "unregister",
                "(L" + ILISTENER + ";)V", false));
        il.add(endLabel);
        il.add(new InsnNode(RETURN));
    }

    private void replaceClearBusID(MethodNode method) {
        clearMethod(method);
        InsnList il = method.instructions;

        // for (ListenerList list : allLists) {
        il.add(new FieldInsnNode(GETSTATIC, OWNER, "allLists", "Ljava/util/List;"));
        il.add(new MethodInsnNode(INVOKEINTERFACE, "java/util/List", "iterator",
                "()Ljava/util/Iterator;", true));
        il.add(new VarInsnNode(ASTORE, 1)); // iterator

        Label loopStart = new Label();
        LabelNode loopStartLabel = new LabelNode(loopStart);
        Label loopEnd = new Label();
        LabelNode loopEndLabel = new LabelNode(loopEnd);

        il.add(loopStartLabel);
        il.add(new VarInsnNode(ALOAD, 1));
        il.add(new MethodInsnNode(INVOKEINTERFACE, "java/util/Iterator", "hasNext",
                "()Z", true));
        il.add(new JumpInsnNode(IFEQ, loopEndLabel));

        il.add(new VarInsnNode(ALOAD, 1));
        il.add(new MethodInsnNode(INVOKEINTERFACE, "java/util/Iterator", "next",
                "()Ljava/lang/Object;", true));
        il.add(new TypeInsnNode(CHECKCAST, OWNER));
        il.add(new VarInsnNode(ASTORE, 2)); // list

        // ListenerListInst inst = list.lists[id];
        il.add(new VarInsnNode(ALOAD, 2));
        il.add(new FieldInsnNode(GETFIELD, OWNER, "lists", INST_ARR));
        il.add(new VarInsnNode(ILOAD, 0)); // id (static method, param 0)
        il.add(new InsnNode(AALOAD));
        il.add(new VarInsnNode(ASTORE, 3)); // inst

        // if (inst != null) inst.dispose();
        Label skipDispose = new Label();
        LabelNode skipDisposeLabel = new LabelNode(skipDispose);
        il.add(new VarInsnNode(ALOAD, 3));
        il.add(new JumpInsnNode(IFNULL, skipDisposeLabel));
        il.add(new VarInsnNode(ALOAD, 3));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, INST, "dispose", "()V", false));
        il.add(skipDisposeLabel);

        il.add(new JumpInsnNode(GOTO, loopStartLabel));
        il.add(loopEndLabel);
        il.add(new InsnNode(RETURN));
    }
}
