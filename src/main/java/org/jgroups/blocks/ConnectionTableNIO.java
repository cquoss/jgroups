// $Id: ConnectionTableNIO.java,v 1.24 2006/09/18 18:00:37 bstansberry Exp $

package org.jgroups.blocks;

import EDU.oswego.cs.dl.util.concurrent.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jgroups.Address;
import org.jgroups.stack.IpAddress;
import org.jgroups.util.Util;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;

/**
 * Manages incoming and outgoing TCP connections. For each outgoing message to destination P, if there
 * is not yet a connection for P, one will be created. Subsequent outgoing messages will use this
 * connection.  For incoming messages, one server socket is created at startup. For each new incoming
 * client connecting, a new thread from a thread pool is allocated and listens for incoming messages
 * until the socket is closed by the peer.<br>Sockets/threads with no activity will be killed
 * after some time.
 * <p/>
 * Incoming messages from any of the sockets can be received by setting the message listener.
 *
 * We currently require use_incoming_packet_handler=true (release 2.4 will support use_incoming_packet_handler=false
 * due to threadless stack support).
 *
 * @author Bela Ban, Scott Marlow, Alex Fu
 */
public class ConnectionTableNIO extends BasicConnectionTable implements Runnable {

   private ServerSocketChannel m_serverSocketChannel;
   private Selector m_acceptSelector;
   protected final static Log LOG = LogFactory.getLog(ConnectionTableNIO.class);

   private WriteHandler[] m_writeHandlers;
   private int m_nextWriteHandler = 0;
   private final Object m_lockNextWriteHandler = new Object();

   private ReadHandler[] m_readHandlers;
   private int m_nextReadHandler = 0;
   private final Object m_lockNextReadHandler = new Object();

   // thread pool for processing read requests
   private Executor m_requestProcessors;
   private volatile boolean serverStopping=false;

   private final LinkedList m_backGroundThreads = new LinkedList();  // Collection of all created threads

   private int m_reader_threads = 8;

   private int m_writer_threads = 8;

   private int m_processor_threads = 10;                    // PooledExecutor.createThreads()
   private int m_processor_minThreads = 10;                 // PooledExecutor.setMinimumPoolSize()
   private int m_processor_maxThreads = 10;                 // PooledExecutor.setMaxThreads()
   private int m_processor_queueSize=100;                   // Number of queued requests that can be pending waiting
   // for a background thread to run the request.
   private int m_processor_keepAliveTime = -1;              // PooledExecutor.setKeepAliveTime( milliseconds);
    // A negative value means to wait forever



   /**
    * @param srv_port
    * @throws Exception
    */
   public ConnectionTableNIO(int srv_port) throws Exception {
      this.srv_port=srv_port;
      start();
   }

   /**
    * @param srv_port
    * @param reaper_interval
    * @param conn_expire_time
    * @throws Exception
    */
   public ConnectionTableNIO(int srv_port, long reaper_interval,
                             long conn_expire_time) throws Exception {
      this.srv_port=srv_port;
      this.reaper_interval=reaper_interval;
      this.conn_expire_time=conn_expire_time;
      start();
   }

   /**
    * @param r
    * @param bind_addr
    * @param external_addr
    * @param srv_port
    * @param max_port
    * @throws Exception
    */
   public ConnectionTableNIO(Receiver r, InetAddress bind_addr, InetAddress external_addr, int srv_port, int max_port
   )
      throws Exception
   {
      setReceiver(r);
      this.external_addr=external_addr;
      this.bind_addr=bind_addr;
      this.srv_port=srv_port;
      this.max_port=max_port;
      use_reaper=true;
      start();
   }


    public ConnectionTableNIO(Receiver r, InetAddress bind_addr, InetAddress external_addr, int srv_port, int max_port,
                              boolean doStart
    )
            throws Exception
    {
        setReceiver(r);
        this.external_addr=external_addr;
        this.bind_addr=bind_addr;
        this.srv_port=srv_port;
        this.max_port=max_port;
        use_reaper=true;
        if(doStart)
            start();
    }


   /**
    * @param r
    * @param bind_addr
    * @param external_addr
    * @param srv_port
    * @param max_port
    * @param reaper_interval
    * @param conn_expire_time
    * @throws Exception
    */
   public ConnectionTableNIO(Receiver r, InetAddress bind_addr, InetAddress external_addr, int srv_port, int max_port,
                             long reaper_interval, long conn_expire_time
                             ) throws Exception
   {
      setReceiver(r);
      this.bind_addr=bind_addr;
      this.external_addr=external_addr;
      this.srv_port=srv_port;
      this.max_port=max_port;
      this.reaper_interval=reaper_interval;
      this.conn_expire_time=conn_expire_time;
      use_reaper=true;
      start();
   }


    public ConnectionTableNIO(Receiver r, InetAddress bind_addr, InetAddress external_addr, int srv_port, int max_port,
                              long reaper_interval, long conn_expire_time, boolean doStart
    ) throws Exception
    {
        setReceiver(r);
        this.bind_addr=bind_addr;
        this.external_addr=external_addr;
        this.srv_port=srv_port;
        this.max_port=max_port;
        this.reaper_interval=reaper_interval;
        this.conn_expire_time=conn_expire_time;
        use_reaper=true;
        if(doStart)
            start();
    }



    public int getReaderThreads() { return m_reader_threads; }

    public void setReaderThreads(int m_reader_threads) {
        this.m_reader_threads=m_reader_threads;
    }

    public int getWriterThreads() { return m_writer_threads; }

    public void setWriterThreads(int m_writer_threads) {
        this.m_writer_threads=m_writer_threads;
    }

    public int getProcessorThreads() { return m_processor_threads; }

    public void setProcessorThreads(int m_processor_threads) {
        this.m_processor_threads=m_processor_threads;
    }

    public int getProcessorMinThreads() { return m_processor_minThreads;}

    public void setProcessorMinThreads(int m_processor_minThreads) {
        this.m_processor_minThreads=m_processor_minThreads;
    }

    public int getProcessorMaxThreads() { return m_processor_maxThreads;}

    public void setProcessorMaxThreads(int m_processor_maxThreads) {
        this.m_processor_maxThreads=m_processor_maxThreads;
    }

    public int getProcessorQueueSize() { return m_processor_queueSize; }

    public void setProcessorQueueSize(int m_processor_queueSize) {
        this.m_processor_queueSize=m_processor_queueSize;
    }

    public int getProcessorKeepAliveTime() { return m_processor_keepAliveTime; }

    public void setProcessorKeepAliveTime(int m_processor_keepAliveTime) {
        this.m_processor_keepAliveTime=m_processor_keepAliveTime;
    }


    /**
    * Try to obtain correct Connection (or create one if not yet existent)
    */
   ConnectionTable.Connection getConnection(Address dest) throws Exception
   {
      Connection conn;
      SocketChannel sock_ch;

      synchronized (conns)
      {
         conn = (Connection) conns.get(dest);
         if (conn == null)
         {
            InetSocketAddress destAddress = new InetSocketAddress(((IpAddress) dest).getIpAddress(),
               ((IpAddress) dest).getPort());
            sock_ch = SocketChannel.open(destAddress);
             sock_ch.socket().setTcpNoDelay(tcp_nodelay);
            conn = new Connection(sock_ch, dest);

            conn.sendLocalAddress(local_addr);
            // This outbound connection is ready

            sock_ch.configureBlocking(false);

            try
            {
               if (LOG.isTraceEnabled())
                  LOG.trace("About to change new connection send buff size from " + sock_ch.socket().getSendBufferSize() + " bytes");
               sock_ch.socket().setSendBufferSize(send_buf_size);
               if (LOG.isTraceEnabled())
                  LOG.trace("Changed new connection send buff size to " + sock_ch.socket().getSendBufferSize() + " bytes");
            }
            catch (IllegalArgumentException ex)
            {
               if (log.isErrorEnabled()) log.error("exception setting send buffer size to " +
                  send_buf_size + " bytes: " + ex);
            }
            try
            {
               if (LOG.isTraceEnabled())
                  LOG.trace("About to change new connection receive buff size from " + sock_ch.socket().getReceiveBufferSize() + " bytes");
               sock_ch.socket().setReceiveBufferSize(recv_buf_size);
               if (LOG.isTraceEnabled())
                  LOG.trace("Changed new connection receive buff size to " + sock_ch.socket().getReceiveBufferSize() + " bytes");
            }
            catch (IllegalArgumentException ex)
            {
               if (log.isErrorEnabled()) log.error("exception setting receive buffer size to " +
                  send_buf_size + " bytes: " + ex);
            }

            int idx;
            synchronized (m_lockNextWriteHandler)
            {
               idx = m_nextWriteHandler = (m_nextWriteHandler + 1) % m_writeHandlers.length;
            }
            conn.setupWriteHandler(m_writeHandlers[idx]);

            // Put the new connection to the queue
            try
            {
               synchronized (m_lockNextReadHandler)
               {
                  idx = m_nextReadHandler = (m_nextReadHandler + 1) % m_readHandlers.length;
               }
               m_readHandlers[idx].add(conn);

            } catch (InterruptedException e)
            {
               if (LOG.isWarnEnabled())
                  LOG.warn("Thread (" +Thread.currentThread().getName() + ") was interrupted, closing connection", e);
               // What can we do? Remove it from table then.
               conn.destroy();
               throw e;
            }

            // Add connection to table
            addConnection(dest, conn);

            notifyConnectionOpened(dest);
            if (LOG.isInfoEnabled()) LOG.info("created socket to " + dest);
         }
         return conn;
      }
   }

   public final void start() throws Exception {
       super.start();
       //Roland Kurmann 4/7/2003, build new thread group
       thread_group = new ThreadGroup(Util.getGlobalThreadGroup(), "ConnectionTableThreads");
       init();
       srv_sock=createServerSocket(srv_port, max_port);

       if (external_addr!=null)
           local_addr=new IpAddress(external_addr, srv_sock.getLocalPort());
       else if (bind_addr != null)
           local_addr=new IpAddress(bind_addr, srv_sock.getLocalPort());
       else
           local_addr=new IpAddress(srv_sock.getLocalPort());

       if(log.isInfoEnabled()) log.info("server socket created on " + local_addr);


       //Roland Kurmann 4/7/2003, put in thread_group
       acceptor=new Thread(thread_group, this, "ConnectionTable.AcceptorThread");
       acceptor.setDaemon(true);
       acceptor.start();
       m_backGroundThreads.add(acceptor);

       // start the connection reaper - will periodically remove unused connections
       if(use_reaper && reaper == null) {
           reaper=new Reaper();
           reaper.start();
       }
   }

   protected void init()
      throws Exception
   {

      // use directExector if max thread pool size is less than or equal to zero.
      if(getProcessorMaxThreads() <= 0) {
         m_requestProcessors = new DirectExecutor();
      }
      else
      {
         // Create worker thread pool for processing incoming buffers
         PooledExecutor requestProcessors = new PooledExecutor(new BoundedBuffer(getProcessorQueueSize()), getProcessorMaxThreads());
          requestProcessors.setThreadFactory(new ThreadFactory() {
              public Thread newThread(Runnable runnable) {
                  Thread new_thread=new Thread(thread_group, runnable);
                  new_thread.setDaemon(true);
                  new_thread.setName("ConnectionTableNIO.Thread");
                  m_backGroundThreads.add(new_thread);
                  return new_thread;
              }
          });
         requestProcessors.setMinimumPoolSize(getProcessorMinThreads());
         requestProcessors.setKeepAliveTime(getProcessorKeepAliveTime());
         requestProcessors.waitWhenBlocked();
         requestProcessors.createThreads(getProcessorThreads());
         m_requestProcessors = requestProcessors;
      }

      m_writeHandlers = WriteHandler.create(getWriterThreads(), thread_group, m_backGroundThreads);
      m_readHandlers = ReadHandler.create(getReaderThreads(), this, thread_group, m_backGroundThreads);
   }


   /**
    * Closes all open sockets, the server socket and all threads waiting for incoming messages
    */
   public void stop()
   {
       super.stop();
      serverStopping = true;

       if(reaper != null)
           reaper.stop();

      // Stop the main selector
      m_acceptSelector.wakeup();

      // Stop selector threads
      for (int i = 0; i < m_readHandlers.length; i++)
      {
         try
         {
            m_readHandlers[i].add(new Shutdown());
         } catch (InterruptedException e)
         {
            LOG.error("Thread ("+Thread.currentThread().getName() +") was interrupted, failed to shutdown selector", e);
         }
      }
      for (int i = 0; i < m_writeHandlers.length; i++)
      {
         try
         {
            m_writeHandlers[i].QUEUE.put(new Shutdown());
            m_writeHandlers[i].SELECTOR.wakeup();
         } catch (InterruptedException e)
         {
            LOG.error("Thread ("+Thread.currentThread().getName() +") was interrupted, failed to shutdown selector", e);
         }
      }

      // Stop the callback thread pool
      if(m_requestProcessors instanceof PooledExecutor)
         ((PooledExecutor)m_requestProcessors).shutdownNow();

       if(m_requestProcessors instanceof PooledExecutor) {
           try {
               ((PooledExecutor)m_requestProcessors).awaitTerminationAfterShutdown(1000);
           }
           catch(InterruptedException e) {
           }
       }

      // then close the connections
      synchronized(conns) {
          Iterator it=conns.values().iterator();
          while(it.hasNext()) {
              Connection conn=(Connection)it.next();
              conn.destroy();
          }
          conns.clear();
      }

      while(m_backGroundThreads.size() > 0) {
          Thread t = (Thread)m_backGroundThreads.removeFirst();
          try {
            t.join();
          } catch(InterruptedException e) {
            LOG.error("Thread ("+Thread.currentThread().getName() +") was interrupted while waiting on thread " + t.getName() + " to finish.");              
          }
      }
      m_backGroundThreads.clear();

   }

   /**
    * Acceptor thread. Continuously accept new connections and assign readhandler/writehandler
    * to them.
    */
   public void run()
   {
      Connection conn;

      while (m_serverSocketChannel.isOpen() && !serverStopping)
      {
         int num;
         try
         {
            num = m_acceptSelector.select();
         } catch (IOException e)
         {
            if (LOG.isWarnEnabled())
               LOG.warn("Select operation on listening socket failed", e);
            continue;   // Give up this time
         }

         if (num > 0)
         {
            Set readyKeys = m_acceptSelector.selectedKeys();
            for (Iterator i = readyKeys.iterator(); i.hasNext();)
            {
               SelectionKey key = (SelectionKey) i.next();
               i.remove();
               // We only deal with new incoming connections

               ServerSocketChannel readyChannel = (ServerSocketChannel) key.channel();
               SocketChannel client_sock_ch;
               try
               {
                  client_sock_ch = readyChannel.accept();
               } catch (IOException e)
               {
                  if (LOG.isWarnEnabled())
                     LOG.warn("Attempt to accept new connection from listening socket failed" , e);
                  // Give up this connection
                  continue;
               }

               if (LOG.isInfoEnabled())
                  LOG.info("accepted connection, client_sock=" + client_sock_ch.socket());

               try {

                  if (LOG.isTraceEnabled())
                     LOG.trace("About to change new connection send buff size from " + client_sock_ch.socket().getSendBufferSize() + " bytes");
                  client_sock_ch.socket().setSendBufferSize(send_buf_size);
                  if (LOG.isTraceEnabled())
                     LOG.trace("Changed new connection send buff size to " + client_sock_ch.socket().getSendBufferSize() + " bytes");
               }
               catch (IllegalArgumentException ex)
               {
                  if (log.isErrorEnabled()) log.error("exception setting send buffer size to " +
                     send_buf_size + " bytes: " ,ex);
               }
               catch (SocketException e)
               {
                  if (log.isErrorEnabled()) log.error("exception setting send buffer size to " +
                     send_buf_size + " bytes: " , e);
               }

               try
               {
                  if (LOG.isTraceEnabled())
                     LOG.trace("About to change new connection receive buff size from " + client_sock_ch.socket().getReceiveBufferSize() + " bytes");
                  client_sock_ch.socket().setReceiveBufferSize(recv_buf_size);
                  if (LOG.isTraceEnabled())
                     LOG.trace("Changed new connection receive buff size to " + client_sock_ch.socket().getReceiveBufferSize() + " bytes");
               }
               catch (IllegalArgumentException ex)
               {
                  if (log.isErrorEnabled()) log.error("exception setting receive buffer size to " +
                     send_buf_size + " bytes: " , ex);
               }
               catch (SocketException e)
               {
                  if (log.isErrorEnabled()) log.error("exception setting receive buffer size to " +
                     recv_buf_size + " bytes: " , e);
               }

               conn = new Connection(client_sock_ch, null);
               try
               {
                  conn.peer_addr = conn.readPeerAddress(client_sock_ch.socket());

                  synchronized (conns)
                  {
                     if (conns.containsKey(conn.getPeerAddress()))
                     {
                        if (conn.getPeerAddress().equals(getLocalAddress()))
                        {
                           if (LOG.isTraceEnabled())
                              LOG.trace(conn.getPeerAddress() + " is myself, not put it in table twice, but still read from it");
                        } else
                        {
                           if (LOG.isWarnEnabled())
                              LOG.warn(conn.getPeerAddress() + " is already there, will terminate connection");
                           // keep existing connection, close this new one
                           conn.destroy();
                           continue;
                        }
                     } else {
                        addConnection(conn.getPeerAddress(), conn);
                     }
                  }
                  notifyConnectionOpened(conn.getPeerAddress());
                  client_sock_ch.configureBlocking(false);
               }
               catch (IOException e)
               {
                  if (LOG.isWarnEnabled())
                     LOG.warn("Attempt to configure non-blocking mode failed", e);
                  // Give up this connection
                  conn.destroy();
                  continue;
               }
               catch (Exception e)
               {
                  if (LOG.isWarnEnabled())
                     LOG.warn("Attempt to handshake with other peer failed", e);
                  // Give up this connection
                  conn.destroy();
                  continue;
               }

               int idx;
               synchronized (m_lockNextWriteHandler)
               {
                  idx = m_nextWriteHandler = (m_nextWriteHandler + 1) % m_writeHandlers.length;
               }
               conn.setupWriteHandler(m_writeHandlers[idx]);

               try
               {
                  synchronized (m_lockNextReadHandler)
                  {
                     idx = m_nextReadHandler = (m_nextReadHandler + 1) % m_readHandlers.length;
                  }
                  m_readHandlers[idx].add(conn);

               } catch (InterruptedException e)
               {
                  if (LOG.isWarnEnabled())
                     LOG.warn("Attempt to configure read handler for accepted connection failed" , e);
                  // close connection
                  conn.destroy();
               }
            }   // end of iteration
         }   // end of selected key > 0
      }   // end of thread

      if (m_serverSocketChannel.isOpen())
      {
         try
         {
            m_serverSocketChannel.close();
         }
         catch (Exception e)
         {
            log.error("exception closing server listening socket", e);
         }
      }
      if (LOG.isTraceEnabled())
         LOG.trace("acceptor thread terminated");

   }


   /**
    * Finds first available port starting at start_port and returns server socket. Sets srv_port
    */
   protected ServerSocket createServerSocket(int start_port, int end_port) throws Exception
   {
      this.m_acceptSelector = Selector.open();
      m_serverSocketChannel = ServerSocketChannel.open();
      m_serverSocketChannel.configureBlocking(false);
      while (true)
      {
         try
         {
            SocketAddress sockAddr;
            if (bind_addr == null)
            {
               sockAddr=new InetSocketAddress(start_port);
               m_serverSocketChannel.socket().bind(sockAddr);
            }
            else
            {
               sockAddr=new InetSocketAddress(bind_addr, start_port);
               m_serverSocketChannel.socket().bind(sockAddr, backlog);
            }
         }
         catch (BindException bind_ex)
         {
            if (start_port == end_port)
               throw (BindException) ((new BindException("No available port to bind to")).initCause(bind_ex));
            start_port++;
            continue;
         }
         catch (SocketException bind_ex)
         {
            if (start_port == end_port)
               throw (BindException) ((new BindException("No available port to bind to")).initCause(bind_ex));
            start_port++;
            continue;
         }
         catch (IOException io_ex)
         {
            if (LOG.isErrorEnabled()) LOG.error("Attempt to bind serversocket failed, port="+start_port+", bind addr=" + bind_addr ,io_ex);
            throw io_ex;
         }
         srv_port = start_port;
         break;
      }
      m_serverSocketChannel.register(this.m_acceptSelector, SelectionKey.OP_ACCEPT);
      return m_serverSocketChannel.socket();
   }

   protected void runRequest(Address addr, ByteBuffer buf) throws InterruptedException {
      m_requestProcessors.execute(new ExecuteTask(addr, buf));
   }


   // Represents shutdown
   private static class Shutdown {
   }

   // ReadHandler has selector to deal with read, it runs in seperated thread
   private static class ReadHandler implements Runnable {
      private final Selector SELECTOR = initHandler();
      private final LinkedQueue QUEUE = new LinkedQueue();
      private final ConnectionTableNIO connectTable;

      ReadHandler(ConnectionTableNIO ct) {
         connectTable= ct;
      }

      public Selector initHandler()
      {
         // Open the selector
         try
         {
            return Selector.open();
         } catch (IOException e)
         {
            if (LOG.isErrorEnabled()) LOG.error(e);
            throw new IllegalStateException(e.getMessage());
         }

      }

      /**
       * create instances of ReadHandler threads for receiving data.
       *
       * @param workerThreads is the number of threads to create.
       */
      private static ReadHandler[] create(int workerThreads, ConnectionTableNIO ct, ThreadGroup tg, LinkedList backGroundThreads)
      {
         ReadHandler[] handlers = new ReadHandler[workerThreads];
         for (int looper = 0; looper < workerThreads; looper++)
         {
            handlers[looper] = new ReadHandler(ct);

            Thread thread = new Thread(tg, handlers[looper], "nioReadHandlerThread");
            thread.setDaemon(true);
            thread.start();
            backGroundThreads.add(thread);
         }
         return handlers;
      }


      private void add(Object conn) throws InterruptedException
      {
         QUEUE.put(conn);
         wakeup();
      }

      private void wakeup()
      {
         SELECTOR.wakeup();
      }

      public void run()
      {
         while (true)
         {  // m_s can be closed by the management thread
            int events;
            try
            {
               events = SELECTOR.select();
            } catch (IOException e)
            {
               if (LOG.isWarnEnabled())
                  LOG.warn("Select operation on socket failed", e);
               continue;   // Give up this time
            } catch (ClosedSelectorException e)
            {
               if (LOG.isWarnEnabled())
                  LOG.warn("Select operation on socket failed" , e);
               return;     // Selector gets closed, thread stops
            }

            if (events > 0)
            {   // there are read-ready channels
               Set readyKeys = SELECTOR.selectedKeys();
               for (Iterator i = readyKeys.iterator(); i.hasNext();)
               {
                  SelectionKey key = (SelectionKey) i.next();
                  i.remove();
                  // Do partial read and handle call back
                  Connection conn = (Connection) key.attachment();
                  try
                  {
                     if (conn.getSocketChannel().isOpen())
                        readOnce(conn);
                     else
                     {  // socket connection is already closed, clean up connection state
                        conn.closed();
                     }
                  } catch (IOException e)
                  {
                     if (LOG.isTraceEnabled()) LOG.trace("Read operation on socket failed" , e);
                     // The connection must be bad, cancel the key, close socket, then
                     // remove it from table!
                     key.cancel();
                     conn.destroy();
                     conn.closed();
                  }
               }
            }

            // Now we look at the connection queue to get any new connections added
            Object o;
            try
            {
               o = QUEUE.poll(0); // get a connection
            } catch (InterruptedException e)
            {
               if (LOG.isInfoEnabled()) LOG.info("Thread ("+Thread.currentThread().getName() +") was interrupted while polling queue" ,e);
               // We must give up
               continue;
            }
            if (null == o)
               continue;
            if (o instanceof Shutdown) {     // shutdown command?
               try {
                  SELECTOR.close();
               } catch(IOException e) {
                  if (LOG.isInfoEnabled()) LOG.info("Read selector close operation failed" , e);
               }
               return;                       // stop reading
            }
            Connection conn = (Connection) o;// must be a new connection
            SocketChannel sc = conn.getSocketChannel();
            try
            {
               sc.register(SELECTOR, SelectionKey.OP_READ, conn);
            } catch (ClosedChannelException e)
            {
               if (LOG.isInfoEnabled()) LOG.info("Socket channel was closed while we were trying to register it to selector" , e);
               // Channel becomes bad. The connection must be bad,
               // close socket, then remove it from table!
               conn.destroy();
               conn.closed();
            }
         }   // end of the while true loop
      }

      private void readOnce(Connection conn)
         throws IOException
      {
         ConnectionReadState readState = conn.getReadState();
         if (!readState.isHeadFinished())
         {  // a brand new message coming or header is not completed
            // Begin or continue to read header
            int size = readHeader(conn);
            if (0 == size)
            {  // header is not completed
               return;
            }
         }
         // Begin or continue to read body
         if (readBody(conn) > 0)
         { // not finish yet
            return;
         }
         Address addr = conn.getPeerAddress();
         ByteBuffer buf = readState.getReadBodyBuffer();
         // Clear status
         readState.bodyFinished();
         // Assign worker thread to execute call back
         try
         {
            connectTable.runRequest(addr, buf);
         } catch (InterruptedException e)
         {
            // Cannot do call back, what can we do?
            // Give up handling the message then
            LOG.error("Thread ("+Thread.currentThread().getName() +") was interrupted while assigning executor to process read request" , e);
         }
      }

      /**
       * Read message header from channel. It doesn't try to complete. If there is nothing in
       * the channel, the method returns immediately.
       *
       * @param conn The connection
       * @return 0 if header hasn't been read completely, otherwise the size of message body
       * @throws IOException
       */
      private int readHeader(Connection conn)
         throws IOException
      {
         ConnectionReadState readState = conn.getReadState();
         ByteBuffer headBuf = readState.getReadHeadBuffer();

         SocketChannel sc = conn.getSocketChannel();
         while (headBuf.remaining() > 0)
         {
            int num = sc.read(headBuf);
            if (-1 == num)
            {// EOS
               throw new IOException("Peer closed socket");
            }
            if (0 == num) // no more data
               return 0;
         }
         // OK, now we get the whole header, change the status and return message size
         return readState.headFinished();
      }

      /**
       * Read message body from channel. It doesn't try to complete. If there is nothing in
       * the channel, the method returns immediately.
       *
       * @param conn The connection
       * @return remaining bytes for the message
       * @throws IOException
       */
      private int readBody(Connection conn)
         throws IOException
      {
         ByteBuffer bodyBuf = conn.getReadState().getReadBodyBuffer();

         SocketChannel sc = conn.getSocketChannel();
         while (bodyBuf.remaining() > 0)
         {
            int num = sc.read(bodyBuf);
            if (-1 == num) // EOS
               throw new IOException("Couldn't read from socket as peer closed the socket");
            if (0 == num) // no more data
               return bodyBuf.remaining();
         }
         // OK, we finished reading the whole message! Flip it (not necessary though)
         bodyBuf.flip();
         return 0;
      }
   }

   private class ExecuteTask implements Runnable {
      Address m_addr = null;
      ByteBuffer m_buf = null;

      public ExecuteTask(Address addr, ByteBuffer buf)
      {
         m_addr = addr;
         m_buf = buf;
      }

      public void run()
      {
         receive(m_addr, m_buf.array(), m_buf.arrayOffset(), m_buf.limit());
      }
   }

   private class ConnectionReadState {
      private final Connection m_conn;

      // Status for receiving message
      private boolean m_headFinished = false;
      private ByteBuffer m_readBodyBuf = null;
      private final ByteBuffer m_readHeadBuf = ByteBuffer.allocate(Connection.HEADER_SIZE);

      public ConnectionReadState(Connection conn)
      {
         m_conn = conn;
      }

      ByteBuffer getReadBodyBuffer()
      {
         return m_readBodyBuf;
      }

      ByteBuffer getReadHeadBuffer()
      {
         return m_readHeadBuf;
      }

      void bodyFinished()
      {
         m_headFinished = false;
         m_readHeadBuf.clear();
         m_readBodyBuf = null;
         m_conn.updateLastAccessed();
      }

      /**
       * Status change for finishing reading the message header (data already in buffer)
       *
       * @return message size
       */
      int headFinished()
      {
         m_headFinished = true;
         m_readHeadBuf.flip();
         int messageSize = m_readHeadBuf.getInt();
         m_readBodyBuf = ByteBuffer.allocate(messageSize);
         m_conn.updateLastAccessed();
         return messageSize;
      }

      boolean isHeadFinished()
      {
         return m_headFinished;
      }
   }

   class Connection extends ConnectionTable.Connection {
      private SocketChannel sock_ch = null;
      private WriteHandler m_writeHandler;
      private SelectorWriteHandler m_selectorWriteHandler;
      private final ConnectionReadState m_readState;

      private static final int HEADER_SIZE = 4;
      final ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);

      Connection(SocketChannel s, Address peer_addr)
      {
         super(s.socket(), peer_addr);
         sock_ch = s;
         m_readState = new ConnectionReadState(this);
          is_running=true;
      }

      private ConnectionReadState getReadState()
      {
         return m_readState;
      }

      private void setupWriteHandler(WriteHandler hdlr)
      {
         m_writeHandler = hdlr;
         m_selectorWriteHandler = hdlr.add(sock_ch);
      }

//      void destroy()
//      {
//         closeSocket();
//      }

      void doSend(byte[] buffie, int offset, int length) throws Exception
      {
         FutureResult result = new FutureResult();
         m_writeHandler.write(sock_ch, ByteBuffer.wrap(buffie, offset, length), result, m_selectorWriteHandler);
         Exception ex = result.getException();
         if (ex != null)
         {
            if (LOG.isErrorEnabled())
               LOG.error("failed sending message", ex);
            if (ex.getCause() instanceof IOException)
               throw (IOException) ex.getCause();
            throw ex;
         }
         result.get();
      }


      SocketChannel getSocketChannel()
      {
         return sock_ch;
      }

      void closeSocket()
      {

         if (sock_ch != null)
         {
            try
            {
               if(sock_ch.isConnected() && sock_ch.isOpen()) {
                  sock_ch.close();
               }
            }
            catch (Exception e)
            {
               log.error("error closing socket connection", e);
            }
            sock_ch = null;
         }
      }


      void closed()
      {
         Address peerAddr = getPeerAddress();
         synchronized (conns)
         {
            conns.remove(peerAddr);
         }
         notifyConnectionClosed(peerAddr);
      }
   }


   /**
    * Handle writing to non-blocking NIO connection.
    */
   private static class WriteHandler implements Runnable {
      // Create a queue for write requests
      private final LinkedQueue QUEUE = new LinkedQueue();

      private final Selector SELECTOR = initSelector();
      private int m_pendingChannels;                 // count of the number of channels that have pending writes
      // note that this variable is only accessed by one thread.

      // allocate and reuse the header for all buffer write operations
      private ByteBuffer m_headerBuffer = ByteBuffer.allocate(Connection.HEADER_SIZE);


      Selector initSelector() {
         try
         {
            return SelectorProvider.provider().openSelector();
         }
         catch (IOException e)
         {
            if (LOG.isErrorEnabled()) LOG.error(e);
            throw new IllegalStateException(e.getMessage());
         }
      }

      /**
       * create instances of WriteHandler threads for sending data.
       *
       * @param workerThreads is the number of threads to create.
       */
      private static WriteHandler[] create(int workerThreads, ThreadGroup tg, LinkedList backGroundThreads)
      {
         WriteHandler[] handlers = new WriteHandler[workerThreads];
         for (int looper = 0; looper < workerThreads; looper++)
         {
            handlers[looper] = new WriteHandler();

            Thread thread = new Thread(tg, handlers[looper], "nioWriteHandlerThread");
            thread.setDaemon(true);
            thread.start();
            backGroundThreads.add(thread);
         }
         return handlers;
      }

      /**
       * Add a new channel to be handled.
       *
       * @param channel
       */
      private SelectorWriteHandler add(SocketChannel channel)
      {
          return new SelectorWriteHandler(channel, SELECTOR, m_headerBuffer);
      }

      /**
       * Writes buffer to the specified socket connection.  This is always performed asynchronously.  If you want
       * to perform a synchrounous write, call notification.`get() which will block until the write operation is complete.
       * Best practice is to call notification.getException() which may return any exceptions that occured during the write
       * operation.
       *
       * @param channel      is where the buffer is written to.
       * @param buffer       is what we write.
       * @param notification may be specified if you want to know how many bytes were written and know if an exception
       *                     occurred.
       */
      private void write(SocketChannel channel, ByteBuffer buffer, FutureResult notification, SelectorWriteHandler hdlr) throws InterruptedException
      {
         QUEUE.put(new WriteRequest(channel, buffer, notification, hdlr));
      }

      private void close(SelectorWriteHandler entry)
      {
         entry.cancel();
      }

      private void handleChannelError( SelectorWriteHandler entry, Throwable error)
      {
         // notify callers of the exception and drain all of the send buffers for this channel.
         do
         {
            if (error != null)
               entry.notifyError(error);
         }
         while (entry.next());
         close(entry);
      }

      // process the write operation
      private void processWrite(Selector selector)
      {
         Set keys = selector.selectedKeys();
         Object arr[] = keys.toArray();
         for (int looper = 0; looper < arr.length; looper++)
         {
            SelectionKey key = (SelectionKey) arr[looper];
            SelectorWriteHandler entry = (SelectorWriteHandler) key.attachment();
            boolean needToDecrementPendingChannels = false;
            try
            {
               if (0 == entry.write())
               {  // write the buffer and if the remaining bytes is zero,
                  // notify the caller of number of bytes written.
                  entry.notifyObject(new Integer(entry.getBytesWritten()));
                  // switch to next write buffer or clear interest bit on socket channel.
                  if (!entry.next())
                  {
                     needToDecrementPendingChannels = true;
                  }
               }

            }
            catch (IOException e)
            {
               needToDecrementPendingChannels = true;
               // connection must of closed
               handleChannelError( entry, e);
            }
            finally
            {
               if (needToDecrementPendingChannels)
                  m_pendingChannels--;
            }
         }
         keys.clear();
      }

      public void run()
      {
         while (SELECTOR.isOpen())
         {
            try
            {
               WriteRequest queueEntry;
               Object o;

               // When there are no more commands in the Queue, we will hit the blocking code after this loop.
               while (null != (o = QUEUE.poll(0)))
               {
                  if (o instanceof Shutdown)    // Stop the thread
                  {
                     try {
                        SELECTOR.close();
                     } catch(IOException e) {
                        if (LOG.isInfoEnabled()) LOG.info("Write selector close operation failed" , e);
                     }
                     return;
                  }
                  queueEntry = (WriteRequest) o;

                  if (queueEntry.getHandler().add(queueEntry))
                  {
                     // If the add operation returns true, than means that a buffer is available to be written to the
                     // corresponding channel and channel's selection key has been modified to indicate interest in the
                     // 'write' operation.
                     // If the add operation threw an exception, we will not increment m_pendingChannels which
                     // seems correct as long as a new buffer wasn't added to be sent.
                     // Another way to view this is that we don't have to protect m_pendingChannels on the increment
                     // side, only need to protect on the decrement side (this logic of this run() will be incorrect
                     // if m_pendingChannels is set incorrectly).
                     m_pendingChannels++;
                  }

                  try
                  {
                     // process any connections ready to be written to.
                     if (SELECTOR.selectNow() > 0)
                     {
                        processWrite(SELECTOR);
                     }
                  }
                  catch (IOException e)
                  {  // need to understand what causes this error so we can handle it properly
                     if (LOG.isErrorEnabled()) LOG.error("SelectNow operation on write selector failed, didn't expect this to occur, please report this", e);
                     return;             // if select fails, give up so we don't go into a busy loop.
                  }
               }

               // if there isn't any pending work to do, block on queue to get next request.
               if (m_pendingChannels == 0)
               {
                  o = QUEUE.take();
                  if (o instanceof Shutdown){    // Stop the thread
                     try {
                        SELECTOR.close();
                     } catch(IOException e) {
                        if (LOG.isInfoEnabled()) LOG.info("Write selector close operation failed" , e);
                     }
                     return;
                  }
                  queueEntry = (WriteRequest) o;
                  if (queueEntry.getHandler().add(queueEntry))
                     m_pendingChannels++;
               }
               // otherwise do a blocking wait select operation.
               else
               {
                  try
                  {
                     if ((SELECTOR.select()) > 0)
                     {
                        processWrite(SELECTOR);
                     }
                  }
                  catch (IOException e)
                  {  // need to understand what causes this error
                     if (LOG.isErrorEnabled()) LOG.error("Failure while writing to socket",e);
                  }
               }
            }
            catch (InterruptedException e)
            {
               if (LOG.isErrorEnabled()) LOG.error("Thread ("+Thread.currentThread().getName() +") was interrupted", e);
            }
            catch (Throwable e)     // Log throwable rather than terminating this thread.
            {                       // We are a daemon thread so we shouldn't prevent the process from terminating if
               // the controlling thread decides that should happen.
               if (LOG.isErrorEnabled()) LOG.error("Thread ("+Thread.currentThread().getName() +") caught Throwable" , e);
            }
         }
      }
   }


   // Wrapper class for passing Write requests.  There will be an instance of this class for each socketChannel
   // mapped to a Selector.
   public static class SelectorWriteHandler {

      private final LinkedList m_writeRequests = new LinkedList();  // Collection of writeRequests
      private boolean m_headerSent = false;
      private SocketChannel m_channel;
      private SelectionKey m_key;
      private Selector m_selector;
      private int m_bytesWritten = 0;
      private boolean m_enabled = false;
      private ByteBuffer m_headerBuffer;

      SelectorWriteHandler(SocketChannel channel, Selector selector, ByteBuffer headerBuffer)
      {
         m_channel = channel;
         m_selector = selector;
         m_headerBuffer = headerBuffer;
      }

      private void register(Selector selector, SocketChannel channel) throws ClosedChannelException
      {
         // register the channel but don't enable OP_WRITE until we have a write request.
         m_key = channel.register(selector, 0, this);
      }

      // return true if selection key is enabled when it wasn't previous to call.
      private boolean enable()
      {
         boolean rc = false;

         try
         {
            if (m_key == null)
            {     // register the socket on first access,
                  // we are the only thread using this variable, so no sync needed.
               register(m_selector, m_channel);
            }
         }
         catch (ClosedChannelException e)
         {
            return rc;
         }

         if (!m_enabled)
         {
            rc = true;
            try
            {
               m_key.interestOps(SelectionKey.OP_WRITE);
            }
            catch (CancelledKeyException e)
            {    // channel must of closed
               return false;
            }
            m_enabled = true;
         }
         return rc;
      }

      private void disable()
      {
         if (m_enabled)
         {
            try
            {
               m_key.interestOps(0);               // pass zero which means that we are not interested in being
                                                   // notified of anything for this channel.
            }
            catch (CancelledKeyException eat)      // If we finished writing and didn't get an exception, then
            {                                      // we probably don't need to throw this exception (if they try to write
                                                   // again, we will then throw an exception).
            }
            m_enabled = false;
         }
      }

      private void cancel()
      {
         m_key.cancel();
      }

      boolean add(WriteRequest entry)
      {
         m_writeRequests.add(entry);
         return enable();
      }

      WriteRequest getCurrentRequest()
      {
         return (WriteRequest) m_writeRequests.getFirst();
      }

      SocketChannel getChannel()
      {
         return m_channel;
      }

      ByteBuffer getBuffer()
      {
         return getCurrentRequest().getBuffer();
      }

      FutureResult getCallback()
      {
         return getCurrentRequest().getCallback();
      }

      int getBytesWritten()
      {
         return m_bytesWritten;
      }

      void notifyError(Throwable error)
      {
         if (getCallback() != null)
            getCallback().setException(error);
      }

      void notifyObject(Object result)
      {
         if (getCallback() != null)
            getCallback().set(result);
      }

      /**
       * switch to next request or disable write interest bit if there are no more buffers.
       *
       * @return true if another request was found to be processed.
       */
      boolean next()
      {
         m_headerSent = false;
         m_bytesWritten = 0;

         m_writeRequests.removeFirst();            // remove current entry
         boolean rc = !m_writeRequests.isEmpty();
         if (!rc)                                  // disable select for this channel if no more entries
            disable();
         return rc;
      }

      /**
       * @return bytes remaining to write.  This function will only throw IOException, unchecked exceptions are not
       *         expected to be thrown from here.  It is very important for the caller to know if an unchecked exception can
       *         be thrown in here.  Please correct the following throws list to include any other exceptions and update
       *         caller to handle them.
       * @throws IOException
       */
      int write() throws IOException
      {
         // Send header first.  Note that while we are writing the shared header buffer,
         // no other threads can access the header buffer as we are the only thread that has access to it.
         if (!m_headerSent)
         {
            m_headerSent = true;
            m_headerBuffer.clear();
            m_headerBuffer.putInt(getBuffer().remaining());
            m_headerBuffer.flip();
            do
            {
               getChannel().write(m_headerBuffer);
            }                                      // we should be able to handle writing the header in one action but just in case, just do a busy loop
            while (m_headerBuffer.remaining() > 0);

         }

         m_bytesWritten += (getChannel().write(getBuffer()));

         return getBuffer().remaining();
      }

   }

   public static class WriteRequest {
      private final SocketChannel m_channel;
      private final ByteBuffer m_buffer;
      private final FutureResult m_callback;
      private final SelectorWriteHandler m_hdlr;

      WriteRequest(SocketChannel channel, ByteBuffer buffer, FutureResult callback, SelectorWriteHandler hdlr)
      {
         m_channel = channel;
         m_buffer = buffer;
         m_callback = callback;
         m_hdlr = hdlr;
      }

      SelectorWriteHandler getHandler()
      {
         return m_hdlr;
      }

      SocketChannel getChannel()
      {
         return m_channel;
      }

      ByteBuffer getBuffer()
      {
         return m_buffer;
      }

      FutureResult getCallback()
      {
         return m_callback;
      }

   }

}
