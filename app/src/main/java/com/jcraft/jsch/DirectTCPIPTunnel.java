//    Copyright (C) 2015, Mike Rieker, Beverly, MA USA
//    www.outerworldapps.com
//
//    This program is free software; you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation; version 2 of the License.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    EXPECT it to FAIL when someone's HeALTh or PROpeRTy is at RISk.
//
//    You should have received a copy of the GNU General Public License
//    along with this program; if not, write to the Free Software
//    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
//    http://www.gnu.org/licenses/gpl-2.0.html

package com.jcraft.jsch;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.LinkedList;

/**
 * Direct access to a TCP/IP tunnel via send calls
 * and receive callbacks.
 */
public class DirectTCPIPTunnel extends Channel {
    public interface Recv {
        void gotData (byte[] data, int offset, int length);
        void gotExtData (byte[] data, int offset, int length);
        void gotEof ();
    }

    private final static int rmpsize = 4096; //TODO random guess

    // copied from ChannelDirectTCPIP
    private final static int LOCAL_WINDOW_SIZE_MAX = 0x20000;
    private final static int LOCAL_MAXIMUM_PACKET_SIZE = 0x4000;

    private int local_port;
    private int remote_port;
    private String local_ipaddr;
    private String remote_host;

    private Buffer sendBuffer;
    private Packet sendPacket;
    private Recv receiver;
    private Session session;
    private ServerSocket serverSocket;

    public DirectTCPIPTunnel (Session sess, String remotehost, int remoteport, Recv rcvr)
            throws Exception
    {
        session  = sess;
        receiver = rcvr;

        // get an unique local port number to send to remote host
        serverSocket = new ServerSocket (0);

        // modeled after Session.setPortForwardingL()

        // modeled after ChannelDirectTCPIP.ctor()

        type = Util.str2byte ("direct-tcpip");
        setLocalWindowSizeMax (LOCAL_WINDOW_SIZE_MAX);
        setLocalWindowSize (LOCAL_WINDOW_SIZE_MAX);
        setLocalPacketSize (LOCAL_MAXIMUM_PACKET_SIZE);
        local_ipaddr = serverSocket.getInetAddress ().toString ();
        local_port   = serverSocket.getLocalPort ();
        remote_host  = remotehost;
        remote_port  = remoteport;
        session.addChannel (this);

        // modeled after ChannelDirectTCPIP.connect()->ChannelDirectTCPIP.run()

        sendChannelOpen ();

        sendBuffer = new Buffer (rmpsize);
        sendPacket = new Packet (sendBuffer);
    }

    /**
     * Get send buffer parameters valid until next sendBuf() call.
     */
    public byte[] getSendBuf ()
    {
        return sendBuffer.buffer;
    }
    public int    getSendOfs ()
    {
        return 14;
    }
    public int    getSendLen ()
    {
        return sendBuffer.buffer.length - 14 - Session.buffer_margin;
    }

    /**
     * Send some data to the remote host.
     */
    public void sendBuf (int length)
            throws Exception
    {
        // modeled after ChannelDirectTCPIP.connect()->ChannelDirectTCPIP.run()

        sendPacket.reset ();
        sendBuffer.putByte ((byte) Session.SSH_MSG_CHANNEL_DATA);
        sendBuffer.putInt (getRecipient ());
        sendBuffer.putInt (length);
        sendBuffer.skip (length);
        synchronized (this) {
            if (!isConnected ()) {
                throw new IOException ("channel closed");
            }
            session.write (sendPacket, this, length);
        }
    }

    /**
     * Send EOF marker on stream to remote host.
     */
    public void sendEof ()
    {
        eof ();
    }

    /**
     * Terminate connection with remote host.
     */
    @Override  // Channel
    public void disconnect ()
    {
        super.disconnect ();
        try { serverSocket.close (); } catch (IOException ioe) { }
        serverSocket = null;
    }

    /**
     * Set up packet with tunneling parameters.
     */
    @Override  // Channel
    protected Packet genChannelOpenPacket()
    {
        // modeled after ChannelDirectTCPIP.genChannelOpenPacket()

        byte[] hostBytes = Util.str2byte (remote_host);
        byte[] oripBytes = Util.str2byte (local_ipaddr);

        Buffer buf = new Buffer (50 + // 6 + 4*8 + 12
                hostBytes.length + oripBytes.length +
                Session.buffer_margin);
        Packet packet = new Packet (buf);
        // byte   SSH_MSG_CHANNEL_OPEN(90)
        // string channel type         //
        // uint32 sender channel       // 0
        // uint32 initial window size  // 0x100000(65536)
        // uint32 maxmum packet size   // 0x4000(16384)
        packet.reset ();
        buf.putByte ((byte) Session.SSH_MSG_CHANNEL_OPEN);
        buf.putString (type);
        buf.putInt (id);
        buf.putInt (lwsize);
        buf.putInt (lmpsize);
        buf.putString (hostBytes);
        buf.putInt (remote_port);
        buf.putString (oripBytes);
        buf.putInt (local_port);
        return packet;
    }

    /**
     * Host sent us someting.
     */
    // host sent us some normal data
    @Override  // Channel
    void write (byte[] data, int offset, int length)
            throws IOException
    {
        receiver.gotData (data, offset, length);
    }

    // host sent us some extended data
    @Override  // Channel
    void write_ext (byte[] data, int offset, int length)
            throws IOException
    {
        receiver.gotExtData (data, offset, length);
    }

    // host sent us an EOF
    @Override  // Channel
    void eof_remote ()
    {
        receiver.gotEof ();
    }
}
