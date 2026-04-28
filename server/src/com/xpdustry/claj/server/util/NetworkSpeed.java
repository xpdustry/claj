/**
 * This file is part of CLaJ. The system that allows you to play with your friends,
 * just by creating a room, copying the link and sending it to your friends.
 * Copyright (c) 2025  Xpdustry
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

package com.xpdustry.claj.server.util;

import java.util.concurrent.atomic.AtomicLong;

import arc.math.WindowedMean;
import arc.util.Time;


/** Calculate speed of an arbitrary thing, per seconds. E.g. network speed; in bytes per seconds. (thread-safe)*/
public class NetworkSpeed {
  protected final WindowedMean upload, download;
  protected volatile long lastUpload, lastDownload;
  protected final AtomicLong uploadAccum, downloadAccum, totalUpload, totalDownload;

  public NetworkSpeed(int windowSec) {
    upload = new WindowedMean(windowSec);
    download = new WindowedMean(windowSec);
    uploadAccum = new AtomicLong();
    downloadAccum = new AtomicLong();
    totalUpload = new AtomicLong();
    totalDownload = new AtomicLong();
    long now = Time.millis();
    lastUpload = now;
    lastDownload = now;
  }

  public void downloadMark() { downloadMark(1); }
  public void downloadMark(int count) {
    long time = Time.millis();
    if (time - lastDownload >= 1000) {
      synchronized (download) {
        if (time - lastDownload >= 1000) { // Be sure
          // Fill holes between calls
          while (time - lastDownload >= 1000) {
            download.add(downloadAccum.getAndSet(0));
            lastDownload += 1000;
          }
        }
      }
    }
    downloadAccum.getAndAdd(count);
    totalDownload.getAndAdd(count);
  }

  public void uploadMark() { uploadMark(1); }
  public void uploadMark(int count) {
    long time = Time.millis();
    if (time - lastUpload >= 1000) {
      synchronized (upload) {
        if (time - lastUpload >= 1000) { // Be sure
          // Fill holes between calls
          while (time - lastUpload >= 1000) {
            upload.add(uploadAccum.getAndSet(0));
            lastUpload += 1000;
          }
        }
      }
    }
    uploadAccum.getAndAdd(count);
    totalUpload.getAndAdd(count);
  }

  /** Number of things per second. E.g. bytes per seconds */
  public float downloadSpeed() {
    synchronized (download) {
      return download.mean();
    }
  }

  /** Number of things per second. E.g. bytes per seconds */
  public float uploadSpeed() {
    synchronized (upload) {
      return upload.mean();
    }
  }

  /** Total number of things. E.g. total bytes */
  public long totalDownload() {
    return totalDownload.get();
  }

  /** Total number of things. E.g. total bytes */
  public long totalUpload() {
    return totalUpload.get();
  }
}