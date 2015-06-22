package com.outerworldapps.sshclient;


import android.content.pm.ActivityInfo;
import android.graphics.Typeface;
import android.widget.TextView;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * @brief Show list of network interfaces and their IP address(es).
 */
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
                String name = niface.getName () + ": " + niface.getDisplayName () + "\n";
                Enumeration<InetAddress> ipaddrit = niface.getInetAddresses ();
                while (ipaddrit.hasMoreElements ()) {
                    if (name != null) {
                        append (name);
                        name = null;
                    }
                    InetAddress ipaddr = ipaddrit.nextElement ();
                    append ("  " + ipaddr.getHostAddress () + "\n");
                }
            }
        } catch (Exception e) {
            append ("\n" + e.toString ());
        }

        // display the menu always in portrait orientation
        sshClient.setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
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
