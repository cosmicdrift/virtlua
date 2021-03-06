/*
 Copyright (c) 2014-2015 Colby Skeggs

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
package io.github.cosmicdrift.virtlua;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;

import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.LuaPrototype;
import se.krka.kahlua.vm.LuaState;

public class SimContext {
    public final LinkedBlockingQueue<SimMessage> toSim;
    public final LinkedBlockingQueue<SimMessage> fromSim;
    private LuaState state;
    private final LuaPrototype bios;
    private LuaClosure main;
    private boolean crashed = false;
    private String version = "Unknown Platform";
    private SimMessage systemInfo = new SimMessage("No Info Available");

    public SimContext(String bios, LinkedBlockingQueue<SimMessage> toSim, LinkedBlockingQueue<SimMessage> fromSim) throws IOException {
        this.bios = LuaCompiler.compilestring(bios, "<bios>");
        this.toSim = toSim;
        this.fromSim = fromSim;
    }

    public SimContext(String rootCode) throws IOException {
        this(rootCode, new LinkedBlockingQueue<SimMessage>(), new LinkedBlockingQueue<SimMessage>());
    }

    public SimContext(SimContext template, LinkedBlockingQueue<SimMessage> toSim, LinkedBlockingQueue<SimMessage> fromSim) {
        this.bios = template.bios;
        this.toSim = toSim;
        this.fromSim = fromSim;
    }

    public SimContext(SimContext template) {
        this(template, new LinkedBlockingQueue<SimMessage>(), new LinkedBlockingQueue<SimMessage>());
    }

    public void post(SimMessage message) {
        toSim.add(message);
    }

    public SimMessage poll() {
        return fromSim.poll();
    }

    public boolean isCrashed() {
        return crashed;
    }

    public boolean simulate(int ticks) { // returns true if there's currently more to process (should resume sooner rather than later) or false if not (can wait a bit)
        if (crashed) {
            return false;
        }
        try {
            if (state == null) {
                state = new LuaState();
                main = new LuaClosure(bios, state.getEnvironment());
                register(state);
                state.startCall(main);
            }
            if (state.continueCall(ticks)) {
                state.startCall(main); // the code returned - go back in on the next round ... after we pause.
                return false;
            } else {
                return true; // more to do
            }
        } catch (Throwable thr) {
            thr.printStackTrace();
            crashed = true;
            return false;
        }
    }

    public void hardReset() {
        state = null;
        fromSim.clear();
        toSim.clear();
        crashed = false;
    }

    private void register(LuaState state) {
        state.getEnvironment().rawset("post", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                Object[] ps = new Object[nArguments];
                for (int i = 0; i < nArguments; i++) {
                    ps[i] = callFrame.get(i);
                }
                fromSim.add(new SimMessage(ps));
                return 0;
            }
        });
        state.getEnvironment().rawset("poll", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                SimMessage m = toSim.poll();
                if (m == null) {
                    return 0;
                } else {
                    for (int i = 0; i < m.length(); i++) {
                        callFrame.push(m.get(i));
                    }
                    return m.length();
                }
            }
        });
        state.getEnvironment().rawset("query_info", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                SimMessage m = systemInfo;
                callFrame.push(version);
                for (int i = 0; i < m.length(); i++) {
                    callFrame.push(m.get(i));
                }
                return 1 + m.length();
            }
        });
        state.getEnvironment().rawset("kexec", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                main = (LuaClosure) callFrame.get(0);
                return 0;
            }
        });
    }

    public void setSystemInfo(String version, Object... rest) {
        this.version = version;
        this.systemInfo = new SimMessage(rest);
    }
}
