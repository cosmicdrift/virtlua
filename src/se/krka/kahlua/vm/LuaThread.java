/*
 Copyright (c) 2014-2015 Colby Skeggs
 Derived from code that was
 Copyright (c) 2008-2009 Kristofer Karlsson <kristofer.karlsson@gmail.com>

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 */
package se.krka.kahlua.vm;

import java.util.ArrayList;
import se.krka.kahlua.stdlib.BaseLib;

public class LuaThread {

    public LuaTable environment;
    public LuaThread parent;
    public String stackTrace = "";
    public final ArrayList<UpValue> liveUpvalues = new ArrayList<>();

    public static final int MAX_STACK_SIZE = 1000;
    public static final int INITIAL_STACK_SIZE = 10;

    private static final int MAX_CALL_FRAME_STACK_SIZE = 100;
    private static final int INITIAL_CALL_FRAME_STACK_SIZE = 10;

    public Object[] objectStack;
    public int top;
    public LuaCallFrame[] callFrameStack;
    public int callFrameTop;
    public LuaState state;
    public int expectedResults;
    boolean needsContextRestore;

    public LuaThread(LuaState state, LuaTable environment) {
        this.state = state;
        this.environment = environment;

        objectStack = new Object[INITIAL_STACK_SIZE];
        callFrameStack = new LuaCallFrame[INITIAL_CALL_FRAME_STACK_SIZE];
    }

    public final LuaCallFrame pushNewCallFrame(LuaClosure closure,
            JavaFunction javaFunction,
            int localBase,
            int returnBase,
            int nArguments,
            boolean fromLua,
            boolean insideCoroutine) {
        setCallFrameStackTop(callFrameTop + 1);
        LuaCallFrame callFrame = currentCallFrame();

        callFrame.localBase = localBase;
        callFrame.returnBase = returnBase;
        callFrame.nArguments = nArguments;
        callFrame.fromLua = fromLua;
        callFrame.insideCoroutine = insideCoroutine;
        callFrame.closure = closure;
        callFrame.javaFunction = javaFunction;
        return callFrame;
    }

    public void popCallFrame() {
        if (isDead()) {
            throw new RuntimeException("Stack underflow");
        }
        setCallFrameStackTop(callFrameTop - 1);
    }

    private void ensureCallFrameStackSize(int index) {
        if (index > MAX_CALL_FRAME_STACK_SIZE) {
            throw new RuntimeException("Stack overflow");
        }
        int oldSize = callFrameStack.length;
        int newSize = oldSize;
        while (newSize <= index) {
            newSize = 2 * newSize;
        }
        if (newSize > oldSize) {
            LuaCallFrame[] newStack = new LuaCallFrame[newSize];
            System.arraycopy(callFrameStack, 0, newStack, 0, oldSize);
            callFrameStack = newStack;
        }
    }

    public final void setCallFrameStackTop(int newTop) {
        if (newTop > callFrameTop) {
            ensureCallFrameStackSize(newTop);
        } else {
            callFrameStackClear(newTop, callFrameTop - 1);
        }
        callFrameTop = newTop;
    }

    private void callFrameStackClear(int startIndex, int endIndex) {
        for (; startIndex <= endIndex; startIndex++) {
            LuaCallFrame callFrame = callFrameStack[startIndex];
            if (callFrame != null) {
                callFrameStack[startIndex].closure = null;
                callFrameStack[startIndex].javaFunction = null;
            }
        }
    }

    private final void ensureStacksize(int index) {
        if (index > MAX_STACK_SIZE) {
            throw new RuntimeException("Stack overflow");
        }
        int oldSize = objectStack.length;
        int newSize = oldSize;
        while (newSize <= index) {
            newSize = 2 * newSize;
        }
        if (newSize > oldSize) {
            Object[] newStack = new Object[newSize];
            System.arraycopy(objectStack, 0, newStack, 0, oldSize);
            objectStack = newStack;
        }
    }

    public final void setTop(int newTop) {
        if (top < newTop) {
            ensureStacksize(newTop);
        } else {
            stackClear(newTop, top - 1);
        }
        top = newTop;
    }

    public final void stackCopy(int startIndex, int destIndex, int len) {
        if (len > 0 && startIndex != destIndex) {
            System.arraycopy(objectStack, startIndex, objectStack, destIndex, len);
        }
    }

    public final void stackClear(int startIndex, int endIndex) {
        for (; startIndex <= endIndex; startIndex++) {
            objectStack[startIndex] = null;
        }
    }

    /*
     * End of stack code
     */
    public final void closeUpvalues(int closeIndex) {
		// close all open upvalues

        int loopIndex = liveUpvalues.size();
        while (--loopIndex >= 0) {
            UpValue uv = liveUpvalues.get(loopIndex);
            if (uv.index < closeIndex) {
                return;
            }
            uv.value = objectStack[uv.index];
            uv.thread = null;
            liveUpvalues.remove(loopIndex);
        }
    }

    public final UpValue findUpvalue(int scanIndex) {
        // TODO: use binary search instead?
        int loopIndex = liveUpvalues.size();
        while (--loopIndex >= 0) {
            UpValue uv = (UpValue) liveUpvalues.get(loopIndex);
            if (uv.index == scanIndex) {
                return uv;
            }
            if (uv.index < scanIndex) {
                break;
            }
        }
        UpValue uv = new UpValue();
        uv.thread = this;
        uv.index = scanIndex;

        liveUpvalues.add(loopIndex + 1, uv);
        return uv;
    }

    public final LuaCallFrame currentCallFrame() {
        if (isDead()) {
            return null;
        }
        LuaCallFrame callFrame = callFrameStack[callFrameTop - 1];
        if (callFrame == null) {
            callFrame = new LuaCallFrame(this);
            callFrameStack[callFrameTop - 1] = callFrame;
        }
        return callFrame;
    }

    public final LuaCallFrame parentCallFrame() {
        if (isDead() || callFrameTop < 2) {
            return null;
        }
        return callFrameStack[callFrameTop - 2];
    }

    public int getTop() {
        return top;
    }

    public LuaCallFrame getParent(int level) {
        BaseLib.luaAssert(level >= 0, "Level must be non-negative");
        int index = callFrameTop - level - 1;
        BaseLib.luaAssert(index >= 0, "Level too high");
        return callFrameStack[index];
    }

    public String getCurrentStackTrace(int level, int count, int haltAt) {
        if (level < 0) {
            level = 0;
        }
        if (count < 0) {
            count = 0;
        }
        StringBuilder buffer = new StringBuilder();
        for (int i = callFrameTop - 1 - level; i >= haltAt; i--) {
            if (count-- <= 0) {
                break;
            }
            buffer.append(getStackTrace(callFrameStack[i]));
        }
        return buffer.toString();
    }

    public void cleanCallFrames(LuaCallFrame callerFrame) {
        LuaCallFrame frame;
        while (true) {
            frame = currentCallFrame();
            if (frame == null || frame == callerFrame) {
                break;
            }
            addStackTrace(frame);
            popCallFrame();
        }
    }

    public void addStackTrace(LuaCallFrame frame) {
        stackTrace += getStackTrace(frame);
    }

    private String getStackTrace(LuaCallFrame frame) {
        if (frame.isLua()) {
            int[] lines = frame.closure.prototype.lines;
            if (lines != null) {
                int pc = frame.pc - 1;
                if (pc >= 0 && pc < lines.length) {
                    return "at " + frame.closure.prototype + ':' + lines[pc] + '\n';
                }
            }
        } else {
            return "at " + frame.javaFunction + '\n';
        }
        return "";
    }

    public boolean isDead() {
        return callFrameTop == 0;
    }
}
