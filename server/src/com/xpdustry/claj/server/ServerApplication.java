/**
 * This file is part of CLaJ. The system that allows you to play with your friends,
 * just by creating a room, copying the link and sending it to your friends.
 * Copyright (c) 2026  Xpdustry
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xpdustry.claj.server;

import arc.*;
import arc.func.Cons;
import arc.mock.*;
import arc.struct.Seq;

import com.xpdustry.claj.server.util.TaskQueue;


public class ServerApplication implements Application {
  protected final MockGraphics graphics;
  protected final Seq<ApplicationListener> listeners = new Seq<>();
  protected final TaskQueue runnables = new TaskQueue();
  protected final Cons<Throwable> exceptionHandler;
  protected final Thread mainLoopThread;
  protected final int timeout;
  protected boolean running = true;

  public ServerApplication(ApplicationListener listener, int nominalFps, Cons<Throwable> exceptionHandler) {
    addListener(listener);
    this.exceptionHandler = exceptionHandler;

    Core.settings = new MockSettings();
    Core.app = this;
    Core.files = new MockFiles();
    Core.graphics = graphics = new MockGraphics();

    timeout = nominalFps <= 0 ? 0 : 1000 / nominalFps;
    mainLoopThread = new Thread(this::mainLoop, "ServerApplication");
    mainLoopThread.setUncaughtExceptionHandler((_, e) -> exceptionHandler.get(e));
    mainLoopThread.start();
  }

  void mainLoop() {
    listeners.each(ApplicationListener::init);

    while (running) {
      if (runnables.size() == 0) {
        synchronized (runnables) {
          if (runnables.size() == 0) { // Be sure
            try { runnables.wait(timeout); }
            catch (InterruptedException e) { break; }
          }
        }
      }
      runnables.run();
      graphics.incrementFrameId();
      //defaultUpdate();
      listeners.each(ApplicationListener::update);
      graphics.updateTime();
    }

    listeners.each(l -> {
      l.pause();
      l.dispose();
    });
    dispose();
  }

  @Override
  public Seq<ApplicationListener> getListeners() {
    return listeners;
  }

  @Override
  public ApplicationType getType() {
    return ApplicationType.headless;
  }

  @Override
  public Thread getMainThread() {
    return mainLoopThread;
  }

  @Override
  public String getClipboardText() {
    return null;
  }

  @Override
  public void setClipboardText(String text) {

  }

  @Override
  public void post(Runnable runnable) {
    if (!runnables.post(runnable)) return;
    synchronized (runnables) {
      runnables.notify();
    }
  }

  @Override
  public void exit() {
    post(() -> running = false);
  }

  /** @return an estimate number of task awaiting to be run. Used for monitoring. */
  public int waitingTasks() {
    return runnables.size();
  }
}
