/**
 * Copyright 2007 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HMsg;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HScannerInterface;
import org.apache.hadoop.hbase.HServerAddress;
import org.apache.hadoop.hbase.HServerInfo;
import org.apache.hadoop.hbase.HServerLoad;
import org.apache.hadoop.hbase.HStoreKey;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.LeaseListener;
import org.apache.hadoop.hbase.Leases;
import org.apache.hadoop.hbase.LocalHBaseCluster;
import org.apache.hadoop.hbase.NotServingRegionException;
import org.apache.hadoop.hbase.RegionServerRunningException;
import org.apache.hadoop.hbase.RemoteExceptionHandler;
import org.apache.hadoop.hbase.UnknownScannerException;
import org.apache.hadoop.hbase.filter.RowFilterInterface;
import org.apache.hadoop.hbase.io.BatchUpdate;
import org.apache.hadoop.hbase.io.Cell;
import org.apache.hadoop.hbase.io.RowResult;
import org.apache.hadoop.hbase.io.HbaseMapWritable;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.ipc.HMasterRegionInterface;
import org.apache.hadoop.hbase.ipc.HRegionInterface;
import org.apache.hadoop.hbase.ipc.HbaseRPC;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.InfoServer;
import org.apache.hadoop.hbase.util.Sleeper;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.net.DNS;
import org.apache.hadoop.util.StringUtils;

/**
 * HRegionServer makes a set of HRegions available to clients.  It checks in with
 * the HMaster. There are many HRegionServers in a single HBase deployment.
 */
public class HRegionServer implements HConstants, HRegionInterface, Runnable {
  static final Log LOG = LogFactory.getLog(HRegionServer.class);
  
  // Set when a report to the master comes back with a message asking us to
  // shutdown.  Also set by call to stop when debugging or running unit tests
  // of HRegionServer in isolation. We use AtomicBoolean rather than
  // plain boolean so we can pass a reference to Chore threads.  Otherwise,
  // Chore threads need to know about the hosting class.
  protected volatile AtomicBoolean stopRequested = new AtomicBoolean(false);
  
  protected volatile AtomicBoolean quiesced = new AtomicBoolean(false);
  
  // Go down hard.  Used if file system becomes unavailable and also in
  // debugging and unit tests.
  protected volatile boolean abortRequested;
  
  // If false, the file system has become unavailable
  protected volatile boolean fsOk;
  
  protected final HServerInfo serverInfo;
  protected final HBaseConfiguration conf;
  private FileSystem fs;
  private Path rootDir;
  private final Random rand = new Random();
  
  // region name -> HRegion
  protected volatile SortedMap<Text, HRegion> onlineRegions =
    Collections.synchronizedSortedMap(new TreeMap<Text, HRegion>());
  protected volatile Map<Text, HRegion> retiringRegions =
    new ConcurrentHashMap<Text, HRegion>();
 
  protected final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private final List<HMsg> outboundMsgs =
    Collections.synchronizedList(new ArrayList<HMsg>());

  final int numRetries;
  protected final int threadWakeFrequency;
  private final int msgInterval;
  private final int serverLeaseTimeout;

  // Remote HMaster
  private HMasterRegionInterface hbaseMaster;

  // Server to handle client requests.  Default access so can be accessed by
  // unit tests.
  final Server server;
  
  // Leases
  private final Leases leases;
  
  // Request counter
  private volatile AtomicInteger requestCount = new AtomicInteger();

  // Info server.  Default access so can be used by unit tests.  REGIONSERVER
  // is name of the webapp and the attribute name used stuffing this instance
  // into web context.
  InfoServer infoServer;
  
  /** region server process name */
  public static final String REGIONSERVER = "regionserver";
  
  /**
   * Thread to shutdown the region server in an orderly manner.  This thread
   * is registered as a shutdown hook in the HRegionServer constructor and is
   * only called when the HRegionServer receives a kill signal.
   */
  class ShutdownThread extends Thread {
    private final HRegionServer instance;
    
    /**
     * @param instance
     */
    public ShutdownThread(HRegionServer instance) {
      this.instance = instance;
    }

    /** {@inheritDoc} */
    @Override
    public void run() {
      LOG.info("Starting shutdown thread.");
      
      // tell the region server to stop and wait for it to complete
      instance.stop();
      instance.join();
      LOG.info("Shutdown thread complete");
    }    
    
  }

  // Compactions
  final CompactSplitThread compactSplitThread;

  // Cache flushing  
  final Flusher cacheFlusher;
  
  // HLog and HLog roller.  log is protected rather than private to avoid
  // eclipse warning when accessed by inner classes
  protected HLog log;
  final LogRoller logRoller;
  final Integer logRollerLock = new Integer(0);
  
  /**
   * Starts a HRegionServer at the default location
   * @param conf
   * @throws IOException
   */
  public HRegionServer(HBaseConfiguration conf) throws IOException {
    this(new HServerAddress(conf.get(REGIONSERVER_ADDRESS,
        DEFAULT_REGIONSERVER_ADDRESS)), conf);
  }
  
  /**
   * Starts a HRegionServer at the specified location
   * @param address
   * @param conf
   * @throws IOException
   */
  public HRegionServer(HServerAddress address, HBaseConfiguration conf)
  throws IOException {  
    this.abortRequested = false;
    this.fsOk = true;
    this.conf = conf;

    // Config'ed params
    this.numRetries =  conf.getInt("hbase.client.retries.number", 2);
    this.threadWakeFrequency = conf.getInt(THREAD_WAKE_FREQUENCY, 10 * 1000);
    this.msgInterval = conf.getInt("hbase.regionserver.msginterval", 3 * 1000);
    this.serverLeaseTimeout =
      conf.getInt("hbase.master.lease.period", 30 * 1000);

    // Cache flushing thread.
    this.cacheFlusher = new Flusher(this);
    
    // Compaction thread
    this.compactSplitThread = new CompactSplitThread(this);
    
    // Log rolling thread
    this.logRoller = new LogRoller(this);

    // Task thread to process requests from Master
    this.worker = new Worker();
    this.workerThread = new Thread(worker);

    // Server to handle client requests
    this.server = HbaseRPC.getServer(this, address.getBindAddress(), 
      address.getPort(), conf.getInt("hbase.regionserver.handler.count", 10),
      false, conf);
    this.serverInfo = new HServerInfo(new HServerAddress(
      new InetSocketAddress(getThisIP(),
      this.server.getListenerAddress().getPort())), System.currentTimeMillis(),
      this.conf.getInt("hbase.regionserver.info.port", 60030));
     this.leases = new Leases(
       conf.getInt("hbase.regionserver.lease.period", 3 * 60 * 1000),
       this.threadWakeFrequency);
     
     // Register shutdown hook for HRegionServer, runs an orderly shutdown
     // when a kill signal is recieved
     Runtime.getRuntime().addShutdownHook(new ShutdownThread(this));
  }

  /**
   * The HRegionServer sticks in this loop until closed. It repeatedly checks
   * in with the HMaster, sending heartbeats & reports, and receiving HRegion 
   * load/unload instructions.
   */
  public void run() {
    boolean quiesceRequested = false;
    // A sleeper that sleeps for msgInterval.
    Sleeper sleeper =
      new Sleeper(this.msgInterval, this.stopRequested);
    try {
      init(reportForDuty(sleeper));
      long lastMsg = 0;
      // Now ask master what it wants us to do and tell it what we have done
      for (int tries = 0; !stopRequested.get();) {
        long now = System.currentTimeMillis();
        if (lastMsg != 0 && (now - lastMsg) >= serverLeaseTimeout) {
          // It has been way too long since we last reported to the master.
          // Commit suicide.
          LOG.fatal("unable to report to master for " + (now - lastMsg) +
            " milliseconds - aborting server");
          abort();
          break;
        }
        if ((now - lastMsg) >= msgInterval) {
          HMsg outboundArray[] = null;
          synchronized(this.outboundMsgs) {
            outboundArray =
              this.outboundMsgs.toArray(new HMsg[outboundMsgs.size()]);
            this.outboundMsgs.clear();
          }

          try {
            this.serverInfo.setLoad(new HServerLoad(requestCount.get(),
                onlineRegions.size()));
            this.requestCount.set(0);
            HMsg msgs[] =
              this.hbaseMaster.regionServerReport(serverInfo, outboundArray);
            lastMsg = System.currentTimeMillis();

            if (this.quiesced.get() && onlineRegions.size() == 0) {
              // We've just told the master we're exiting because we aren't
              // serving any regions. So set the stop bit and exit.
              LOG.info("Server quiesced and not serving any regions. " +
              "Starting shutdown");
              stopRequested.set(true);
              continue;
            }

            // Queue up the HMaster's instruction stream for processing
            boolean restart = false;
            for(int i = 0; i < msgs.length && !stopRequested.get() &&
            !restart; i++) {
              switch(msgs[i].getMsg()) {

              case HMsg.MSG_CALL_SERVER_STARTUP:
                LOG.info("Got call server startup message");
                // We the MSG_CALL_SERVER_STARTUP on startup but we can also
                // get it when the master is panicing because for instance
                // the HDFS has been yanked out from under it.  Be wary of
                // this message.
                if (checkFileSystem()) {
                  closeAllRegions();
                  synchronized (logRollerLock) {
                    try {
                      log.closeAndDelete();

                    } catch (Exception e) {
                      LOG.error("error closing and deleting HLog", e);
                    }
                    try {
                      serverInfo.setStartCode(System.currentTimeMillis());
                      log = setupHLog();
                    } catch (IOException e) {
                      this.abortRequested = true;
                      this.stopRequested.set(true);
                      e = RemoteExceptionHandler.checkIOException(e); 
                      LOG.fatal("error restarting server", e);
                      break;
                    }
                  }
                  reportForDuty(sleeper);
                  restart = true;
                } else {
                  LOG.fatal("file system available check failed. " +
                  "Shutting down server.");
                }
                break;

              case HMsg.MSG_REGIONSERVER_STOP:
                LOG.info("Got regionserver stop message");
                stopRequested.set(true);
                break;

              case HMsg.MSG_REGIONSERVER_QUIESCE:
                if (!quiesceRequested) {
                  LOG.info("Got quiesce server message");
                  try {
                    toDo.put(new ToDoEntry(msgs[i]));
                  } catch (InterruptedException e) {
                    throw new RuntimeException("Putting into msgQueue was " +
                        "interrupted.", e);
                  }
                  quiesceRequested = true;
                }
                break;

              default:
                if (fsOk) {
                  try {
                    toDo.put(new ToDoEntry(msgs[i]));
                  } catch (InterruptedException e) {
                    throw new RuntimeException("Putting into msgQueue was " +
                        "interrupted.", e);
                  }
                  if (msgs[i].getMsg() == HMsg.MSG_REGION_OPEN) {
                    this.outboundMsgs.add(new HMsg(HMsg.MSG_REPORT_PROCESS_OPEN,
                      msgs[i].getRegionInfo()));
                  }
                }
              }
            }
            if (restart || this.stopRequested.get()) {
              toDo.clear();
              break;
            }
            // Reset tries count if we had a successful transaction.
            tries = 0;
          } catch (Exception e) {
            if (e instanceof IOException) {
              e = RemoteExceptionHandler.checkIOException((IOException) e);
            }
            if (tries < this.numRetries) {
              LOG.warn("Processing message (Retry: " + tries + ")", e);
              tries++;
            } else {
              LOG.fatal("Exceeded max retries: " + this.numRetries, e);
              if (!checkFileSystem()) {
                continue;
              }
              // Something seriously wrong. Shutdown.
              stop();
            }
          }
        }
        sleeper.sleep(lastMsg);
      } // for
    } catch (Throwable t) {
      LOG.fatal("Unhandled exception. Aborting...", t);
      abort();
    }
    this.leases.closeAfterLeasesExpire();
    this.worker.stop();
    this.server.stop();
    if (this.infoServer != null) {
      LOG.info("Stopping infoServer");
      try {
        this.infoServer.stop();
      } catch (InterruptedException ex) {
        ex.printStackTrace();
      }
    }

    // Send interrupts to wake up threads if sleeping so they notice shutdown.
    // TODO: Should we check they are alive?  If OOME could have exited already
    cacheFlusher.interruptPolitely();
    compactSplitThread.interrupt();
    synchronized (logRollerLock) {
      this.logRoller.interrupt();
    }

    if (abortRequested) {
      if (this.fsOk) {
        // Only try to clean up if the file system is available
        try {
          this.log.close();
          LOG.info("On abort, closed hlog");
        } catch (IOException e) {
          LOG.error("Unable to close log in abort",
              RemoteExceptionHandler.checkIOException(e));
        }
        closeAllRegions(); // Don't leave any open file handles
      }
      LOG.info("aborting server at: " +
        serverInfo.getServerAddress().toString());
    } else {
      ArrayList<HRegion> closedRegions = closeAllRegions();
      try {
        log.closeAndDelete();
      } catch (IOException e) {
        LOG.error("Close and delete failed",
            RemoteExceptionHandler.checkIOException(e));
      }
      try {
        HMsg[] exitMsg = new HMsg[closedRegions.size() + 1];
        exitMsg[0] = new HMsg(HMsg.MSG_REPORT_EXITING);
        // Tell the master what regions we are/were serving
        int i = 1;
        for(HRegion region: closedRegions) {
          exitMsg[i++] = new HMsg(HMsg.MSG_REPORT_CLOSE,
              region.getRegionInfo());
        }

        LOG.info("telling master that region server is shutting down at: " +
            serverInfo.getServerAddress().toString());
        hbaseMaster.regionServerReport(serverInfo, exitMsg);
      } catch (IOException e) {
        LOG.warn("Failed to send exiting message to master: ",
            RemoteExceptionHandler.checkIOException(e));
      }
      LOG.info("stopping server at: " +
        serverInfo.getServerAddress().toString());
    }

    join();
    LOG.info(Thread.currentThread().getName() + " exiting");
  }
  
  /*
   * Run init. Sets up hlog and starts up all server threads.
   * @param c Extra configuration.
   */
  private void init(final HbaseMapWritable c) throws IOException {
    try {
      for (Map.Entry<Writable, Writable> e: c.entrySet()) {
        String key = e.getKey().toString();
        String value = e.getValue().toString();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Config from master: " + key + "=" + value);
        }
        this.conf.set(key, value);
      }
      // Master sent us hbase.rootdir to use. Should be fully qualified
      // path with file system specification included.  Set 'fs.default.name'
      // to match the filesystem on hbase.rootdir else underlying hadoop hdfs
      // accessors will be going against wrong filesystem (unless all is set
      // to defaults).
      this.conf.set("fs.default.name", this.conf.get("hbase.rootdir"));
      this.fs = FileSystem.get(this.conf);
      this.rootDir = new Path(this.conf.get(HConstants.HBASE_DIR));
      this.log = setupHLog();
      startServiceThreads();
    } catch (IOException e) {
      this.stopRequested.set(true);
      e = RemoteExceptionHandler.checkIOException(e); 
      LOG.fatal("Failed init", e);
      IOException ex = new IOException("region server startup failed");
      ex.initCause(e);
      throw ex;
    }
  }
  
  private HLog setupHLog() throws RegionServerRunningException,
    IOException {
    
    Path logdir = new Path(rootDir, "log" + "_" + getThisIP() + "_" +
        this.serverInfo.getStartCode() + "_" + 
        this.serverInfo.getServerAddress().getPort());
    if (LOG.isDebugEnabled()) {
      LOG.debug("Log dir " + logdir);
    }
    if (fs.exists(logdir)) {
      throw new RegionServerRunningException("region server already " +
        "running at " + this.serverInfo.getServerAddress().toString() +
        " because logdir " + logdir.toString() + " exists");
    }
    return new HLog(fs, logdir, conf, logRoller);
  }
  
  /*
   * Start Chore Threads, Server, Worker and lease checker threads. Install an
   * UncaughtExceptionHandler that calls abort of RegionServer if we get
   * an unhandled exception.  We cannot set the handler on all threads.
   * Server's internal Listener thread is off limits.  For Server, if an OOME,
   * it waits a while then retries.  Meantime, a flush or a compaction that
   * tries to run should trigger same critical condition and the shutdown will
   * run.  On its way out, this server will shut down Server.  Leases are sort
   * of inbetween. It has an internal thread that while it inherits from
   * Chore, it keeps its own internal stop mechanism so needs to be stopped
   * by this hosting server.  Worker logs the exception and exits.
   */
  private void startServiceThreads() throws IOException {
    String n = Thread.currentThread().getName();
    UncaughtExceptionHandler handler = new UncaughtExceptionHandler() {
      public void uncaughtException(Thread t, Throwable e) {
        abort();
        LOG.fatal("Set stop flag in " + t.getName(), e);
      }
    };
    Threads.setDaemonThreadRunning(this.logRoller, n + ".logRoller",
        handler);
    Threads.setDaemonThreadRunning(this.cacheFlusher, n + ".cacheFlusher",
      handler);
    Threads.setDaemonThreadRunning(this.compactSplitThread, n + ".compactor",
        handler);
    Threads.setDaemonThreadRunning(this.workerThread, n + ".worker", handler);
    // Leases is not a Thread. Internally it runs a daemon thread.  If it gets
    // an unhandled exception, it will just exit.
    this.leases.setName(n + ".leaseChecker");
    this.leases.start();
    // Put up info server.
    int port = this.conf.getInt("hbase.regionserver.info.port", 60030);
    if (port >= 0) {
      String a = this.conf.get("hbase.master.info.bindAddress", "0.0.0.0");
      this.infoServer = new InfoServer("regionserver", a, port, false);
      this.infoServer.setAttribute("regionserver", this);
      this.infoServer.start();
    }
    // Start Server.  This service is like leases in that it internally runs
    // a thread.
    this.server.start();
    LOG.info("HRegionServer started at: " +
        serverInfo.getServerAddress().toString());
  }

  /** @return the HLog */
  HLog getLog() {
    return this.log;
  }

  /*
   * Use interface to get the 'real' IP for this host. 'serverInfo' is sent to
   * master.  Should have the real IP of this host rather than 'localhost' or
   * 0.0.0.0 or 127.0.0.1 in it.
   * @return This servers' IP.
   */
  private String getThisIP() throws UnknownHostException {
    return DNS.getDefaultIP(conf.get("dfs.datanode.dns.interface","default"));
  }

  /**
   * Sets a flag that will cause all the HRegionServer threads to shut down
   * in an orderly fashion.  Used by unit tests and called by Flusher
   * if it judges server needs to be restarted.
   */
  public synchronized void stop() {
    this.stopRequested.set(true);
    notifyAll();                        // Wakes run() if it is sleeping
  }
  
  /**
   * Cause the server to exit without closing the regions it is serving, the
   * log it is using and without notifying the master.
   * Used unit testing and on catastrophic events such as HDFS is yanked out
   * from under hbase or we OOME.
   */
  public synchronized void abort() {
    this.abortRequested = true;
    stop();
  }

  /** 
   * Wait on all threads to finish.
   * Presumption is that all closes and stops have already been called.
   */
  void join() {
    join(this.workerThread);
    join(this.cacheFlusher);
    join(this.compactSplitThread);
    join(this.logRoller);
  }

  private void join(final Thread t) {
    while (t.isAlive()) {
      try {
        t.join();
      } catch (InterruptedException e) {
        // continue
      }
    }
  }
  
  /*
   * Let the master know we're here
   * Run initialization using parameters passed us by the master.
   */
  private HbaseMapWritable reportForDuty(final Sleeper sleeper)
  throws IOException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Telling master at " +
        conf.get(MASTER_ADDRESS) + " that we are up");
    }
    // Do initial RPC setup.
    this.hbaseMaster = (HMasterRegionInterface)HbaseRPC.waitForProxy(
      HMasterRegionInterface.class, HMasterRegionInterface.versionID,
      new HServerAddress(conf.get(MASTER_ADDRESS)).getInetSocketAddress(),
      this.conf);
    HbaseMapWritable result = null;
    long lastMsg = 0;
    while(!stopRequested.get()) {
      try {
        this.requestCount.set(0);
        this.serverInfo.setLoad(new HServerLoad(0, onlineRegions.size()));
        lastMsg = System.currentTimeMillis();
        result = this.hbaseMaster.regionServerStartup(serverInfo);
        if (LOG.isDebugEnabled()) {
          LOG.debug("Done telling master we are up");
        }
        break;
      } catch(IOException e) {
        LOG.warn("error telling master we are up", e);
        sleeper.sleep(lastMsg);
        continue;
      }
    }
    return result;
  }

  /** Add to the outbound message buffer */
  private void reportOpen(HRegionInfo region) {
    outboundMsgs.add(new HMsg(HMsg.MSG_REPORT_OPEN, region));
  }

  /** Add to the outbound message buffer */
  private void reportClose(HRegionInfo region) {
    outboundMsgs.add(new HMsg(HMsg.MSG_REPORT_CLOSE, region));
  }
  
  /**
   * Add to the outbound message buffer
   * 
   * When a region splits, we need to tell the master that there are two new 
   * regions that need to be assigned.
   * 
   * We do not need to inform the master about the old region, because we've
   * updated the meta or root regions, and the master will pick that up on its
   * next rescan of the root or meta tables.
   */
  void reportSplit(HRegionInfo oldRegion, HRegionInfo newRegionA,
      HRegionInfo newRegionB) {

    outboundMsgs.add(new HMsg(HMsg.MSG_REPORT_SPLIT, oldRegion));
    outboundMsgs.add(new HMsg(HMsg.MSG_REPORT_OPEN, newRegionA));
    outboundMsgs.add(new HMsg(HMsg.MSG_REPORT_OPEN, newRegionB));
  }

  //////////////////////////////////////////////////////////////////////////////
  // HMaster-given operations
  //////////////////////////////////////////////////////////////////////////////

  private static class ToDoEntry {
    int tries;
    HMsg msg;
    ToDoEntry(HMsg msg) {
      this.tries = 0;
      this.msg = msg;
    }
  }
  BlockingQueue<ToDoEntry> toDo = new LinkedBlockingQueue<ToDoEntry>();
  private Worker worker;
  private Thread workerThread;
  
  /** Thread that performs long running requests from the master */
  class Worker implements Runnable {
    void stop() {
      synchronized(toDo) {
        toDo.notifyAll();
      }
    }
    
    /** {@inheritDoc} */
    public void run() {
      try {
        while(!stopRequested.get()) {
          ToDoEntry e = null;
          try {
            e = toDo.poll(threadWakeFrequency, TimeUnit.MILLISECONDS);
            if(e == null || stopRequested.get()) {
              continue;
            }
            LOG.info(e.msg.toString());
            switch(e.msg.getMsg()) {

            case HMsg.MSG_REGIONSERVER_QUIESCE:
              closeUserRegions();
              break;

            case HMsg.MSG_REGION_OPEN:
              // Open a region
              openRegion(e.msg.getRegionInfo());
              break;

            case HMsg.MSG_REGION_CLOSE:
              // Close a region
              closeRegion(e.msg.getRegionInfo(), true);
              break;

            case HMsg.MSG_REGION_CLOSE_WITHOUT_REPORT:
              // Close a region, don't reply
              closeRegion(e.msg.getRegionInfo(), false);
              break;

            default:
              throw new AssertionError(
                  "Impossible state during msg processing.  Instruction: "
                  + e.msg.toString());
            }
          } catch (InterruptedException ex) {
            // continue
          } catch (Exception ex) {
            if (ex instanceof IOException) {
              ex = RemoteExceptionHandler.checkIOException((IOException) ex);
            }
            if(e != null && e.tries < numRetries) {
              LOG.warn(ex);
              e.tries++;
              try {
                toDo.put(e);
              } catch (InterruptedException ie) {
                throw new RuntimeException("Putting into msgQueue was " +
                    "interrupted.", ex);
              }
            } else {
              LOG.error("unable to process message" +
                  (e != null ? (": " + e.msg.toString()) : ""), ex);
              if (!checkFileSystem()) {
                break;
              }
            }
          }
        }
      } catch(Throwable t) {
        LOG.fatal("Unhandled exception", t);
      } finally {
        LOG.info("worker thread exiting");
      }
    }
  }
  
  void openRegion(final HRegionInfo regionInfo) {
    HRegion region = onlineRegions.get(regionInfo.getRegionName());
    if(region == null) {
      try {
        region = new HRegion(
            HTableDescriptor.getTableDir(rootDir,
                regionInfo.getTableDesc().getName()
            ),
            this.log, this.fs, conf, regionInfo, null, this.cacheFlusher
        );
        // Startup a compaction early if one is needed.
        this.compactSplitThread.compactionRequested(region);
      } catch (IOException e) {
        LOG.error("error opening region " + regionInfo.getRegionName(), e);
        
        // TODO: add an extra field in HRegionInfo to indicate that there is
        // an error. We can't do that now because that would be an incompatible
        // change that would require a migration
        reportClose(regionInfo);
        return;
      }
      this.lock.writeLock().lock();
      try {
        this.log.setSequenceNumber(region.getMinSequenceId());
        this.onlineRegions.put(region.getRegionName(), region);
      } finally {
        this.lock.writeLock().unlock();
      }
      reportOpen(regionInfo); 
    }
  }

  void closeRegion(final HRegionInfo hri, final boolean reportWhenCompleted)
  throws IOException {  
    this.lock.writeLock().lock();
    HRegion region = null;
    try {
      region = onlineRegions.remove(hri.getRegionName());
    } finally {
      this.lock.writeLock().unlock();
    }
      
    if(region != null) {
      region.close();
      if(reportWhenCompleted) {
        reportClose(hri);
      }
    }
  }

  /** Called either when the master tells us to restart or from stop() */
  ArrayList<HRegion> closeAllRegions() {
    ArrayList<HRegion> regionsToClose = new ArrayList<HRegion>();
    this.lock.writeLock().lock();
    try {
      regionsToClose.addAll(onlineRegions.values());
      onlineRegions.clear();
    } finally {
      this.lock.writeLock().unlock();
    }
    for(HRegion region: regionsToClose) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("closing region " + region.getRegionName());
      }
      try {
        region.close(abortRequested, null);
      } catch (IOException e) {
        LOG.error("error closing region " + region.getRegionName(),
          RemoteExceptionHandler.checkIOException(e));
      }
    }
    return regionsToClose;
  }

  /** Called as the first stage of cluster shutdown. */
  void closeUserRegions() {
    ArrayList<HRegion> regionsToClose = new ArrayList<HRegion>();
    this.lock.writeLock().lock();
    try {
      synchronized (onlineRegions) {
        for (Iterator<Map.Entry<Text, HRegion>> i =
          onlineRegions.entrySet().iterator();
        i.hasNext();) {
          Map.Entry<Text, HRegion> e = i.next();
          HRegion r = e.getValue();
          if (!r.getRegionInfo().isMetaRegion()) {
            regionsToClose.add(r);
            i.remove();
          }
        }
      }
    } finally {
      this.lock.writeLock().unlock();
    }
    for(HRegion region: regionsToClose) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("closing region " + region.getRegionName());
      }
      try {
        region.close();
      } catch (IOException e) {
        LOG.error("error closing region " + region.getRegionName(),
          RemoteExceptionHandler.checkIOException(e));
      }
    }
    this.quiesced.set(true);
    if (onlineRegions.size() == 0) {
      outboundMsgs.add(new HMsg(HMsg.MSG_REPORT_EXITING));
    } else {
      outboundMsgs.add(new HMsg(HMsg.MSG_REPORT_QUIESCED));
    }
  }

  //
  // HRegionInterface
  //

  /** {@inheritDoc} */
  public HRegionInfo getRegionInfo(final Text regionName)
    throws NotServingRegionException {
    
    requestCount.incrementAndGet();
    return getRegion(regionName).getRegionInfo();
  }

  /** {@inheritDoc} */
  public Cell get(final Text regionName, final Text row, final Text column) 
  throws IOException {
    checkOpen();
    requestCount.incrementAndGet();
    try {
      return getRegion(regionName).get(row, column);      
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }

  /** {@inheritDoc} */
  public Cell[] get(final Text regionName, final Text row,
    final Text column, final int numVersions) 
  throws IOException {
    checkOpen();
    requestCount.incrementAndGet();
    try {
      return getRegion(regionName).get(row, column, numVersions);
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }

  /** {@inheritDoc} */
  public Cell[] get(final Text regionName, final Text row, final Text column, 
    final long timestamp, final int numVersions) 
  throws IOException {
    checkOpen();
    requestCount.incrementAndGet();
    try {
      return getRegion(regionName).get(row, column, timestamp, numVersions);
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }

  /** {@inheritDoc} */
  public HbaseMapWritable getRow(final Text regionName, final Text row, final long ts)
  throws IOException {
    return getRow(regionName, row, null, ts);
  }

  /** {@inheritDoc} */
  public HbaseMapWritable getRow(final Text regionName, final Text row, 
    final Text[] columns)
  throws IOException {
    return getRow(regionName, row, columns, HConstants.LATEST_TIMESTAMP);
  }

  /** {@inheritDoc} */
  public HbaseMapWritable getRow(final Text regionName, final Text row, 
    final Text[] columns, final long ts)
  throws IOException {
    checkOpen();
    requestCount.incrementAndGet();
    try {
      // convert the columns array into a set so it's easy to check later.
      Set<Text> columnSet = new HashSet<Text>();
      if (columns != null) {
        columnSet.addAll(Arrays.asList(columns));
      }
      
      HRegion region = getRegion(regionName);
      Map<Text, Cell> map = region.getFull(row, columnSet, ts);
      HbaseMapWritable result = new HbaseMapWritable();
      for (Map.Entry<Text, Cell> es: map.entrySet()) {
        result.put(new HStoreKey(row, es.getKey()), es.getValue());
      }
      return result;
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }

  /** {@inheritDoc} */
  public HbaseMapWritable getClosestRowBefore(final Text regionName, 
    final Text row)
  throws IOException {
    return getClosestRowBefore(regionName, row, HConstants.LATEST_TIMESTAMP);
  }

  /** {@inheritDoc} */
  public HbaseMapWritable getClosestRowBefore(final Text regionName, 
    final Text row, final long ts)
  throws IOException {
    checkOpen();
    requestCount.incrementAndGet();
    try {
      // locate the region we're operating on
      HRegion region = getRegion(regionName);
      HbaseMapWritable result = new HbaseMapWritable();
      // ask the region for all the data 
      Map<Text, Cell> map = region.getClosestRowBefore(row, ts);
      // convert to a MapWritable
      if (map == null) {
        return null;
      }
      for (Map.Entry<Text, Cell> es: map.entrySet()) {
        result.put(new HStoreKey(row, es.getKey()), es.getValue());
      }
      return result;
      
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }

  /** {@inheritDoc} */
  public RowResult next(final long scannerId) throws IOException {
    checkOpen();
    requestCount.incrementAndGet();
    try {
      String scannerName = String.valueOf(scannerId);
      HScannerInterface s = scanners.get(scannerName);
      if (s == null) {
        throw new UnknownScannerException("Name: " + scannerName);
      }
      this.leases.renewLease(scannerName);

      // Collect values to be returned here
      HbaseMapWritable values = new HbaseMapWritable();
      HStoreKey key = new HStoreKey();
      TreeMap<Text, byte []> results = new TreeMap<Text, byte []>();
      while (s.next(key, results)) {
        for(Map.Entry<Text, byte []> e: results.entrySet()) {
          values.put(e.getKey(), new Cell(e.getValue(), key.getTimestamp()));
        }

        if(values.size() > 0) {
          // Row has something in it. Return the value.
          break;
        }

        // No data for this row, go get another.
        results.clear();
      }
      return new RowResult(key.getRow(), values);
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }

  /** {@inheritDoc} */
  public void batchUpdate(Text regionName, BatchUpdate b)
    throws IOException {
    checkOpen();
    this.requestCount.incrementAndGet();
    HRegion region = getRegion(regionName);
    try {
      region.batchUpdate(b);
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }
  
  //
  // remote scanner interface
  //

  /** {@inheritDoc} */
  public long openScanner(Text regionName, Text[] cols, Text firstRow,
    final long timestamp, final RowFilterInterface filter)
  throws IOException {
    checkOpen();
    requestCount.incrementAndGet();
    try {
      HRegion r = getRegion(regionName);
      long scannerId = -1L;
      HScannerInterface s =
        r.getScanner(cols, firstRow, timestamp, filter);
      scannerId = rand.nextLong();
      String scannerName = String.valueOf(scannerId);
      synchronized(scanners) {
        scanners.put(scannerName, s);
      }
      this.leases.
        createLease(scannerName, new ScannerListener(scannerName));
      return scannerId;
    } catch (IOException e) {
      LOG.error("Error opening scanner (fsOk: " + this.fsOk + ")",
          RemoteExceptionHandler.checkIOException(e));
      checkFileSystem();
      throw e;
    }
  }
  
  /** {@inheritDoc} */
  public void close(final long scannerId) throws IOException {
    checkOpen();
    requestCount.incrementAndGet();
    try {
      String scannerName = String.valueOf(scannerId);
      HScannerInterface s = null;
      synchronized(scanners) {
        s = scanners.remove(scannerName);
      }
      if(s == null) {
        throw new UnknownScannerException(scannerName);
      }
      s.close();
      this.leases.cancelLease(scannerName);
    } catch (IOException e) {
      checkFileSystem();
      throw e;
    }
  }

  Map<String, HScannerInterface> scanners =
    Collections.synchronizedMap(new HashMap<String, HScannerInterface>());

  /** 
   * Instantiated as a scanner lease.
   * If the lease times out, the scanner is closed
   */
  private class ScannerListener implements LeaseListener {
    private final String scannerName;
    
    ScannerListener(final String n) {
      this.scannerName = n;
    }
    
    /** {@inheritDoc} */
    public void leaseExpired() {
      LOG.info("Scanner " + this.scannerName + " lease expired");
      HScannerInterface s = null;
      synchronized(scanners) {
        s = scanners.remove(this.scannerName);
      }
      if (s != null) {
        try {
          s.close();
        } catch (IOException e) {
          LOG.error("Closing scanner", e);
        }
      }
    }
  }
  
  //
  // Methods that do the actual work for the remote API
  //
  
  /** {@inheritDoc} */
  public void deleteAll(final Text regionName, final Text row,
      final Text column, final long timestamp) 
  throws IOException {
    HRegion region = getRegion(regionName);
    region.deleteAll(row, column, timestamp);
  }

  /** {@inheritDoc} */
  public void deleteAll(final Text regionName, final Text row,
      final long timestamp) 
  throws IOException {
    HRegion region = getRegion(regionName);
    region.deleteAll(row, timestamp);
  }

  /** {@inheritDoc} */
  public void deleteFamily(Text regionName, Text row, Text family, 
    long timestamp) throws IOException{
    getRegion(regionName).deleteFamily(row, family, timestamp);
  }


  /**
   * @return Info on this server.
   */
  public HServerInfo getServerInfo() {
    return this.serverInfo;
  }

  /** @return the info server */
  /**
   * Get the InfoServer this HRegionServer has put up.
   */
  public InfoServer getInfoServer() {
    return infoServer;
  }
  
  /**
   * Check if a stop has been requested.
   */
  public boolean isStopRequested() {
    return stopRequested.get();
  }

  /** Get the write lock for the server */
  ReentrantReadWriteLock.WriteLock getWriteLock() {
    return lock.writeLock();
  }

  void compactionRequested(QueueEntry e) {
    compactSplitThread.compactionRequested(e);
  }

  /**
   * @return Immutable list of this servers regions.
   */
  public SortedMap<Text, HRegion> getOnlineRegions() {
    return Collections.unmodifiableSortedMap(this.onlineRegions);
  }

  /** @return the request count */
  public AtomicInteger getRequestCount() {
    return this.requestCount;
  }

  /** @return reference to CacheFlushListener */
  public CacheFlushListener getCacheFlushListener() {
    return this.cacheFlusher;
  }
  
  /** 
   * Protected utility method for safely obtaining an HRegion handle.
   * @param regionName Name of online {@link HRegion} to return
   * @return {@link HRegion} for <code>regionName</code>
   * @throws NotServingRegionException
   */
  protected HRegion getRegion(final Text regionName)
  throws NotServingRegionException {
    return getRegion(regionName, false);
  }
  
  /** Move a region from online to closing. */
  void setRegionClosing(final Text regionName) {
    retiringRegions.put(regionName, onlineRegions.remove(regionName));
  }
  
  /** Set a region as closed. */
  void setRegionClosed(final Text regionName) {
    retiringRegions.remove(regionName);
  }
  
  /** 
   * Protected utility method for safely obtaining an HRegion handle.
   * @param regionName Name of online {@link HRegion} to return
   * @param checkRetiringRegions Set true if we're to check retiring regions
   * as well as online regions.
   * @return {@link HRegion} for <code>regionName</code>
   * @throws NotServingRegionException
   */
  protected HRegion getRegion(final Text regionName,
    final boolean checkRetiringRegions)
  throws NotServingRegionException {
    HRegion region = null;
    this.lock.readLock().lock();
    try {
      region = onlineRegions.get(regionName);
      if (region == null && checkRetiringRegions) {
        region = this.retiringRegions.get(regionName);
        if (LOG.isDebugEnabled()) {
          if (region != null) {
            LOG.debug("Found region " + regionName + " in retiringRegions");
          }
        }
      }

      if (region == null) {
        throw new NotServingRegionException(regionName.toString());
      }
      
      return region;
    } finally {
      this.lock.readLock().unlock();
    }
  }

  /**
   * Called to verify that this server is up and running.
   * 
   * @throws IOException
   */
  private void checkOpen() throws IOException {
    if (this.stopRequested.get() || this.abortRequested) {
      throw new IOException("Server not running");
    }
    if (!fsOk) {
      throw new IOException("File system not available");
    }
  }
  
  /**
   * Checks to see if the file system is still accessible.
   * If not, sets abortRequested and stopRequested
   * 
   * @return false if file system is not available
   */
  protected boolean checkFileSystem() {
    if (this.fsOk) {
      try {
        if (fs != null && !FSUtils.isFileSystemAvailable(fs)) {
          LOG.fatal("Shutting down HRegionServer: file system not available");
          this.abortRequested = true;
          this.stopRequested.set(true);
          fsOk = false;
        }
      } catch (Exception e) {
        LOG.error("Failed get of filesystem", e);
        LOG.fatal("Shutting down HRegionServer: file system not available");
        this.abortRequested = true;
        this.stopRequested.set(true);
        fsOk = false;
      }
    }
    return this.fsOk;
  }
 
  /**
   * @return Returns list of non-closed regions hosted on this server.  If no
   * regions to check, returns an empty list.
   */
  protected Set<HRegion> getRegionsToCheck() {
    HashSet<HRegion> regionsToCheck = new HashSet<HRegion>();
    //TODO: is this locking necessary? 
    lock.readLock().lock();
    try {
      regionsToCheck.addAll(this.onlineRegions.values());
    } finally {
      lock.readLock().unlock();
    }
    // Purge closed regions.
    for (final Iterator<HRegion> i = regionsToCheck.iterator(); i.hasNext();) {
      HRegion r = i.next();
      if (r.isClosed()) {
        i.remove();
      }
    }
    return regionsToCheck;
  }

  /** {@inheritDoc} */
  public long getProtocolVersion(final String protocol, 
      @SuppressWarnings("unused") final long clientVersion)
  throws IOException {  
    if (protocol.equals(HRegionInterface.class.getName())) {
      return HRegionInterface.versionID;
    }
    throw new IOException("Unknown protocol to name node: " + protocol);
  }

  //
  // Main program and support routines
  //
  
  private static void printUsageAndExit() {
    printUsageAndExit(null);
  }
  
  private static void printUsageAndExit(final String message) {
    if (message != null) {
      System.err.println(message);
    }
    System.err.println("Usage: java " +
        "org.apache.hbase.HRegionServer [--bind=hostname:port] start");
    System.exit(0);
  }
  
  /**
   * Do class main.
   * @param args
   * @param regionServerClass HRegionServer to instantiate.
   */
  protected static void doMain(final String [] args,
      final Class<? extends HRegionServer> regionServerClass) {
    if (args.length < 1) {
      printUsageAndExit();
    }
    Configuration conf = new HBaseConfiguration();
    
    // Process command-line args. TODO: Better cmd-line processing
    // (but hopefully something not as painful as cli options).
    final String addressArgKey = "--bind=";
    for (String cmd: args) {
      if (cmd.startsWith(addressArgKey)) {
        conf.set(REGIONSERVER_ADDRESS, cmd.substring(addressArgKey.length()));
        continue;
      }
      
      if (cmd.equals("start")) {
        try {
          // If 'local', don't start a region server here.  Defer to
          // LocalHBaseCluster.  It manages 'local' clusters.
          if (LocalHBaseCluster.isLocal(conf)) {
            LOG.warn("Not starting a distinct region server because " +
              "hbase.master is set to 'local' mode");
          } else {
            Constructor<? extends HRegionServer> c =
              regionServerClass.getConstructor(HBaseConfiguration.class);
            HRegionServer hrs = c.newInstance(conf);
            Thread t = new Thread(hrs);
            t.setName("regionserver" + hrs.server.getListenerAddress());
            t.start();
          }
        } catch (Throwable t) {
          LOG.error( "Can not start region server because "+
              StringUtils.stringifyException(t) );
          System.exit(-1);
        }
        break;
      }
      
      if (cmd.equals("stop")) {
        printUsageAndExit("To shutdown the regionserver run " +
        		"bin/hbase-daemon.sh stop regionserver or send a kill signal to" +
        		"the regionserver pid");
      }
      
      // Print out usage if we get to here.
      printUsageAndExit();
    }
  }
  
  /**
   * @param args
   */
  public static void main(String [] args) {
    doMain(args, HRegionServer.class);
  }
}
