/**
 * Show list of network interfaces and their IP address(es).
 */

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

package com.outerworldapps.sshclient;


import android.annotation.SuppressLint;
import android.graphics.Typeface;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

@SuppressLint("ViewConstructor")
public class NetworkInterfacesView extends TextView {

    private SshClient sshClient;

    public NetworkInterfacesView (SshClient ctx)
    {
        super (ctx);
        sshClient = ctx;
        setTextSize (SshClient.UNIFORM_TEXT_SIZE);
        setTypeface (Typeface.MONOSPACE);
    }

    public void show ()
    {
        setText ("");

        try {
            Enumeration<NetworkInterface> nifaceit = NetworkInterface.getNetworkInterfaces ();
            while (nifaceit.hasMoreElements ()) {
                NetworkInterface niface = nifaceit.nextElement ();
                StringBuilder namesb = new StringBuilder ();
                namesb.append (niface.getName ());
                namesb.append (": ");
                namesb.append (niface.getDisplayName ());
                Class<? extends NetworkInterface> niclass = niface.getClass ();
                try {
                    @SuppressWarnings("JavaReflectionMemberAccess")
                    Method ghwa = niclass.getMethod ("getHardwareAddress");
                    byte[] hwabin = (byte[]) ghwa.invoke (niface);
                    if (hwabin != null) {
                        namesb.append (" hw ");
                        boolean first = true;
                        for (byte hwabyte : hwabin) {
                            if (!first) namesb.append (':');
                            namesb.append (Integer.toHexString ((hwabyte & 0xFF) | 0x100).substring (1));
                            first = false;
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                }
                namesb.append ('\n');
                Enumeration<InetAddress> ipaddrit = niface.getInetAddresses ();
                while (ipaddrit.hasMoreElements ()) {
                    if (namesb != null) {
                        append (namesb);
                        namesb = null;
                    }
                    InetAddress ipaddr = ipaddrit.nextElement ();
                    append ("  " + ipaddr.getHostAddress () + "\n");
                }
            }
        } catch (Exception e) {
            append ("\n" + e.toString ());
        }

        sshClient.setContentView (this);

        // set up method to be called if this screen is back-buttoned to
        sshClient.pushBackAction (new SshClient.BackAction () {
            @Override
            public boolean okToPop ()
            {
                // it is always ok to back-button away from this page
                return true;
            }

            @Override
            public void reshow ()
            {
                show ();
            }

            @Override
            public String name ()
            {
                return "networkinterfaces";
            }

            @Override
            public MySession session ()
            {
                return null;
            }
        });
    }
}
