/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (c) 2013-2015, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.fibers;

import co.paralleluniverse.common.util.Debug;
import java.io.Serializable;
import java.util.Arrays;

/**
 * Internal Class - DO NOT USE! (Public so that instrumented code can access it)
 *
 * ANY CHANGE IN THIS CLASS NEEDS TO BE SYNCHRONIZED WITH {@link co.paralleluniverse.fibers.instrument.InstrumentMethod}
 *
 * @author Matthias Mann
 * @author Ron Pressler
 */
public final class Stack implements Serializable, Cloneable {
    /*
     * sp points to the first slot to contain data.
     * The _previous_ FRAME_RECORD_SIZE slots contain the frame record.
     * The frame record currently occupies a single long:
     *   - entry (PC)         : 14 bits
     *   - num slots          : 16 bits
     *   - prev method slots  : 16 bits
     */
    public static final int MAX_ENTRY = (1 << 14) - 1;
    public static final int MAX_SLOTS = (1 << 16) - 1;
    private static final int INITIAL_METHOD_STACK_DEPTH = 16;
    private static final int FRAME_RECORD_SIZE = 1;
    private static final long serialVersionUID = 12786283751253L;
    private final Object context;
    private int sp;
    private transient boolean shouldVerifyInstrumentation;
    private transient boolean pushed;
    private long[] dataLong;        // holds primitives on stack as well as each method's entry point and the stack pointer
    private Object[] dataObject;    // holds refs on stack

    Stack(Object context, int stackSize) {
        if (stackSize <= 0)
            throw new IllegalArgumentException("stackSize: " + stackSize);

        this.context = context;
        this.dataLong = new long[stackSize + (FRAME_RECORD_SIZE * INITIAL_METHOD_STACK_DEPTH)];
        this.dataObject = new Object[stackSize + (FRAME_RECORD_SIZE * INITIAL_METHOD_STACK_DEPTH)];

        resumeStack();
    }

    @Override
    public Stack clone() {
        try {
            Stack o = (Stack)super.clone();
            o.dataLong = Arrays.copyOf(dataLong, dataLong.length);
            o.dataObject = Arrays.copyOf(dataObject, dataObject.length);
            return o;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }

    public static Stack getStack() {
        final Fiber<?> currentFiber = Fiber.currentFiber();
        if (currentFiber != null)
            return currentFiber.stack;
        final Continuation<?, ?> currentCont = Continuation.getCurrentContinuation();
        if (currentCont != null)
            return currentCont.getStack();
        return null;
    }

    Fiber getFiber() {
        return context instanceof Fiber ? (Fiber) context : null;
    }

    public Continuation getContinuation() {
        return (Continuation) context;
    }

    /**
     * called when resuming a stack
     */
    final void resumeStack() {
        sp = 0;
    }

    // for testing/benchmarking only
    void resetStack() {
        resumeStack();
    }

    /**
     * called at the beginning of a method
     *
     * @return the entry point of this method
     */
    public final int nextMethodEntry() {
        shouldVerifyInstrumentation = true;

        int idx = 0;
        int slots = 0;
        if (sp > 0) {
            slots = getNumSlots(dataLong[sp - FRAME_RECORD_SIZE]);
            idx = sp + slots;
        }
        sp = idx + FRAME_RECORD_SIZE;
        long record = dataLong[idx];
        int entry = getEntry(record);
        dataLong[idx] = setPrevNumSlots(record, slots);
        if (Debug.isDebug() && isRecordingLevel(2))
            record(2, "Stack", "nextMethodEntry", "%s %s %s", Thread.currentThread().getStackTrace()[2], entry, sp /*Arrays.toString(fiber.getStackTrace())*/);

        return entry;
    }

    /**
     * called when nextMethodEntry returns 0
     */
    public final boolean isFirstInStackOrPushed() {

        boolean p = pushed;
        pushed = false;

        if (sp == FRAME_RECORD_SIZE | p)
            return true;

        // not first, but nextMethodEntry returned 0: revert changes
        sp -= FRAME_RECORD_SIZE + getPrevNumSlots(dataLong[sp - FRAME_RECORD_SIZE]);

        return false;
    }

    /**
     * Called before a method is called.
     *
     * @param entry    the entry point in the current method for resume
     * @param numSlots the number of required stack slots for storing the state of the current method
     */
    public final void pushMethod(int entry, int numSlots) {
        shouldVerifyInstrumentation = false;
        pushed = true;

        int idx = sp - FRAME_RECORD_SIZE;
        long record = dataLong[idx];
        record = setEntry(record, entry);
        record = setNumSlots(record, numSlots);
        dataLong[idx] = record;

        int nextMethodIdx = sp + numSlots;
        int nextMethodSP = nextMethodIdx + FRAME_RECORD_SIZE;
        if (nextMethodSP > dataObject.length)
            growStack(nextMethodSP);

        // clear next method's frame record
        dataLong[nextMethodIdx] = 0L;
//        for (int i = 0; i < FRAME_RECORD_SIZE; i++)
//            dataLong[nextMethodIdx + i] = 0L;

        if (Debug.isDebug() && isRecordingLevel(2))
            record(2, "Stack", "pushMethod     ", "%s %s %s %s %d", Thread.currentThread().getStackTrace()[2], entry, sp /*Arrays.toString(fiber.getStackTrace())*/);
    }

    public final void popMethod() {
        if (shouldVerifyInstrumentation) {
            Fiber.verifySuspend(null);
            shouldVerifyInstrumentation = false;
        }
        pushed = false;

        final int oldSP = sp;
        final int idx = oldSP - FRAME_RECORD_SIZE;
        final long record = dataLong[idx];
        final int slots = getNumSlots(record);
        final int newSP = idx - getPrevNumSlots(record);

        // clear frame record (probably unnecessary)
        dataLong[idx] = 0L;
//        for (int i = 0; i < FRAME_RECORD_SIZE; i++)
//            dataLong[idx + i] = 0L;
        // help GC
        for (int i = oldSP; i < oldSP + slots; i++)
            dataObject[i] = null;

        sp = newSP;

        if (Debug.isDebug() && isRecordingLevel(2))
            record(2, "Stack", "popMethod      ", "%s %s %s", Thread.currentThread().getStackTrace()[2], sp /*Arrays.toString(fiber.getStackTrace())*/);
    }

    public final void postRestore() throws SuspendExecution, InterruptedException {
        if (context instanceof Fiber)
            ((Fiber) context).onResume();
    }

//    public final void preemptionPoint(int type) throws SuspendExecution {
//        fiber.preemptionPoint(type);
//    }
    private void growStack(int required) {
        int newSize = dataObject.length;
        do {
            newSize *= 2;
        } while (newSize < required);

        dataLong = Arrays.copyOf(dataLong, newSize);
        dataObject = Arrays.copyOf(dataObject, newSize);
    }

    void dump() {
        int m = 0;
        int k = 0;
        while (k < sp - 1) {
            final long record = dataLong[k++];
            final int slots = getNumSlots(record);

            System.err.println("\tm=" + (m++) + " entry=" + getEntry(record) + " sp=" + k + " slots=" + slots + " prevSlots=" + getPrevNumSlots(record));
            for (int i = 0; i < slots; i++, k++)
                System.err.println("\t\tsp=" + k + " long=" + dataLong[k] + " obj=" + dataObject[k]);
        }
    }

    public static void push(int value, Stack s, int idx) {
//        if (s.fiber.isRecordingLevel(3))
//            s.fiber.record(3, "Stack", "push", "%d (%d) %s", idx, s.sp + idx, value);
        s.dataLong[s.sp + idx] = value;
    }

    public static void push(float value, Stack s, int idx) {
//        if (s.fiber.isRecordingLevel(3))
//            s.fiber.record(3, "Stack", "push", "%d (%d) %s", idx, s.sp + idx, value);
        s.dataLong[s.sp + idx] = Float.floatToRawIntBits(value);
    }

    public static void push(long value, Stack s, int idx) {
//        if (s.fiber.isRecordingLevel(3))
//            s.fiber.record(3, "Stack", "push", "%d (%d) %s", idx, s.sp + idx, value);
        s.dataLong[s.sp + idx] = value;
    }

    public static void push(double value, Stack s, int idx) {
//        if (s.fiber.isRecordingLevel(3))
//            s.fiber.record(3, "Stack", "push", "%d (%d) %s", idx, s.sp + idx, value);
        s.dataLong[s.sp + idx] = Double.doubleToRawLongBits(value);
    }

    public static void push(Object value, Stack s, int idx) {
//        if (s.fiber.isRecordingLevel(3))
//            s.fiber.record(3, "Stack", "push", "%d (%d) %s", idx, s.sp + idx, value);
        s.dataObject[s.sp + idx] = value;
    }

    public final int getInt(int idx) {
        return (int) dataLong[sp + idx];
//        final int value = (int) dataLong[sp + idx];
//        if (fiber.isRecordingLevel(3))
//            fiber.record(3, "Stack", "getInt", "%d (%d) %s", idx, sp + idx, value);
//        return value;
    }

    public final float getFloat(int idx) {
        return Float.intBitsToFloat((int) dataLong[sp + idx]);
//        final float value = Float.intBitsToFloat((int) dataLong[sp + idx]);
//        if (fiber.isRecordingLevel(3))
//            fiber.record(3, "Stack", "getFloat", "%d (%d) %s", idx, sp + idx, value);
//        return value;
    }

    public final long getLong(int idx) {
        return dataLong[sp + idx];
//        final long value = dataLong[sp + idx];
//        if (fiber.isRecordingLevel(3))
//            fiber.record(3, "Stack", "getLong", "%d (%d) %s", idx, sp + idx, value);
//        return value;
    }

    public final double getDouble(int idx) {
        return Double.longBitsToDouble(dataLong[sp + idx]);
//        final double value = Double.longBitsToDouble(dataLong[sp + idx]);
//        if (fiber.isRecordingLevel(3))
//            fiber.record(3, "Stack", "getDouble", "%d (%d) %s", idx, sp + idx, value);
//        return value;
    }

    public final Object getObject(int idx) {
        return dataObject[sp + idx];
//        final Object value = dataObject[sp + idx];
//        if (fiber.isRecordingLevel(3))
//            fiber.record(3, "Stack", "getObject", "%d (%d) %s", idx, sp + idx, value);
//        return value;
    }

    ///////////////////////////////////////////////////////////////
    private long setEntry(long record, int entry) {
        return setBits(record, 0, 14, entry);
    }

    private int getEntry(long record) {
        return (int) getUnsignedBits(record, 0, 14);
    }

    private long setNumSlots(long record, int numSlots) {
        return setBits(record, 14, 16, numSlots);
    }

    private int getNumSlots(long record) {
        return (int) getUnsignedBits(record, 14, 16);
    }

    private long setPrevNumSlots(long record, int numSlots) {
        return setBits(record, 30, 16, numSlots);
    }

    private int getPrevNumSlots(long record) {
        return (int) getUnsignedBits(record, 30, 16);
    }
    ///////////////////////////////////////////////////////////////
    private static final long MASK_FULL = 0xffffffffffffffffL;

    private static long getUnsignedBits(long word, int offset, int length) {
        int a = 64 - length;
        int b = a - offset;
        return (word >>> b) & (MASK_FULL >>> a);
    }

    private static long getSignedBits(long word, int offset, int length) {
        int a = 64 - length;
        int b = a - offset;
        long xx = (word >>> b) & (MASK_FULL >>> a);
        return (xx << a) >> a; // set sign
    }

    private static long setBits(long word, int offset, int length, long value) {
        int a = 64 - length;
        int b = a - offset;
        //long mask = (MASK_FULL >>> a);
        word = word & ~((MASK_FULL >>> a) << b); // clears bits in our region [offset, offset+length)
        // value = value & mask;
        word = word | (value << b);
        return word;
    }

    private static boolean getBit(long word, int offset) {
        return (getUnsignedBits(word, offset, 1) != 0);
    }

    private static long setBit(long word, int offset, boolean value) {
        return setBits(word, offset, 1, value ? 1 : 0);
    }

    static class TraceLine {
        final String method;
        final int line;
        final boolean pushed;

        TraceLine(String method, int line, boolean pushed) {
            this.method = method;
            this.line = line;
            this.pushed = pushed;
        }

        TraceLine(String method, int line) {
            this(method, line, true);
        }
    }

    //////////////////////////////
    protected final boolean isRecordingLevel(int level) {
        if (context instanceof Fiber)
            return ((Fiber) context).isRecordingLevel(level);
        return ((Continuation) context).isRecordingLevel(level);
    }

    protected final void record(int level, String clazz, String method, String format) {
        if (context instanceof Fiber)
            ((Fiber) context).record(level, clazz, method, format);
        ((Continuation) context).record(level, clazz, method, format);
    }

    protected final void record(int level, String clazz, String method, String format, Object arg1) {
        if (context instanceof Fiber)
            ((Fiber) context).record(level, clazz, method, format, arg1);
        ((Continuation) context).record(level, clazz, method, format, arg1);
    }

    protected final void record(int level, String clazz, String method, String format, Object arg1, Object arg2) {
        if (context instanceof Fiber)
            ((Fiber) context).record(level, clazz, method, format, arg1, arg2);
        ((Continuation) context).record(level, clazz, method, format, arg1);
    }

    protected final void record(int level, String clazz, String method, String format, Object arg1, Object arg2, Object arg3) {
        if (context instanceof Fiber)
            ((Fiber) context).record(level, clazz, method, format, arg1, arg2, arg3);
        ((Continuation) context).record(level, clazz, method, format, arg1, arg2, arg3);
    }
}
