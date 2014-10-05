/**
 * Tunnel and a view to edit it with.
 */

//    Copyright (C) 2014, Mike Rieker, Beverly, MA USA
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

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

public class TunnelView extends ScrollView {
    public static final String TAG = "SshClient";

    public AlertDialog editDialog;

    private EditText connectHost;
    private EditText connectPort;
    private EditText listenPort;
    private int actualPort;
    private RadioButton connectLocal;
    private RadioButton connectRemote;
    private RadioButton listenLocal;
    private RadioButton listenRemote;
    private Session jsession;

    public TunnelView (SshClient sshclient, Session jses)
    {
        super (sshclient);

        jsession = jses;

        LinearLayout ll = new LinearLayout (sshclient);
        ll.setOrientation (LinearLayout.VERTICAL);

        TextView tv1 = sshclient.MyTextView ();
        tv1.setText ("Listens on ...");
        ll.addView (tv1);
        RadioGroup rg1 = new RadioGroup (sshclient);
        listenLocal = new RadioButton (sshclient);
        listenLocal.setText ("local device");
        rg1.addView (listenLocal);
        listenRemote = new RadioButton (sshclient);
        listenRemote.setText ("remote host");
        rg1.addView (listenRemote);
        ll.addView (rg1);

        TextView tv2 = sshclient.MyTextView ();
        tv2.setText ("Port ...");
        ll.addView (tv2);
        listenPort = new EditText (sshclient);
        listenPort.setInputType (InputType.TYPE_CLASS_NUMBER);
        listenPort.setSingleLine (true);
        ll.addView (listenPort);

        TextView tv3 = sshclient.MyTextView ();
        tv3.setText ("Connects to ...");
        ll.addView (tv3);
        RadioGroup rg3 = new RadioGroup (sshclient);
        connectLocal = new RadioButton (sshclient);
        connectLocal.setText ("local device");
        rg3.addView (connectLocal);
        connectRemote = new RadioButton (sshclient);
        connectRemote.setText ("remote host");
        rg3.addView (connectRemote);
        ll.addView (rg3);

        TextView tv4 = sshclient.MyTextView ();
        tv4.setText ("Host ...");
        ll.addView (tv4);
        connectHost = new EditText (sshclient);
        connectHost.setInputType (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        connectHost.setSingleLine (true);
        connectHost.setText ("localhost");
        ll.addView (connectHost);

        TextView tv5 = sshclient.MyTextView ();
        tv5.setText ("Port ...");
        ll.addView (tv5);
        connectPort = new EditText (sshclient);
        connectPort.setInputType (InputType.TYPE_CLASS_NUMBER);
        connectPort.setSingleLine (true);
        ll.addView (connectPort);

        // clicking one of the connect buttons clicks the opposite listen button
        connectLocal.setOnClickListener (new OnClickListener () {
            @Override
            public void onClick (View view) {
                if (listenRemote.isChecked () ^ connectLocal.isChecked ()) {
                    listenRemote.setChecked (connectLocal.isChecked ());
                }
            }
        });
        connectRemote.setOnClickListener (new OnClickListener () {
            @Override
            public void onClick (View view) {
                if (listenLocal.isChecked () ^ connectRemote.isChecked ()) {
                    listenLocal.setChecked (connectRemote.isChecked ());
                }
            }
        });

        // clicking one of the listen buttons clicks the opposite connect button
        listenLocal.setOnClickListener (new OnClickListener () {
            @Override
            public void onClick (View view) {
                if (connectRemote.isChecked () ^ listenLocal.isChecked ()) {
                    connectRemote.setChecked (listenLocal.isChecked ());
                }
            }
        });
        listenRemote.setOnClickListener (new OnClickListener () {
            @Override
            public void onClick (View view) {
                if (connectLocal.isChecked () ^ listenRemote.isChecked ()) {
                    connectLocal.setChecked (listenRemote.isChecked ());
                }
            }
        });

        addView (ll);
    }

    /**
     * Summary string to place on a button to select it.
     */
    public String getLabel ()
    {
        StringBuilder sb = new StringBuilder ();
        if (listenLocal.isChecked ()) {
            int listenport = Integer.parseInt (listenPort.getText ().toString ().trim ());
            sb.append (Integer.toString (listenport));
            if (isOpen () && (actualPort != listenport)) {
                sb.append (" (");
                sb.append (Integer.toString (actualPort));
                sb.append (')');
            }
            sb.append (" => ");
            sb.append (connectHost.getText ().toString ().trim ());
            sb.append (':');
            sb.append (connectPort.getText ().toString ().trim ());
        } else {
            sb.append (connectHost.getText ().toString ().trim ());
            sb.append (':');
            sb.append (connectPort.getText ().toString ().trim ());
            sb.append (" <= ");
            sb.append (listenPort.getText ().toString ().trim ());
        }
        return sb.toString ();
    }

    /**
     * Convert parameters to a string that can be saved to a database.
     */
    public String serialize ()
    {
        if (!validate ().equals ("")) return null;

        StringBuilder sb = new StringBuilder ();
        sb.append ("listenLocal=");  sb.append (Boolean.toString (listenLocal.isChecked ()));
        sb.append ("&listenPort=");  sb.append (listenPort.getText ().toString ().trim ());
        sb.append ("&connectHost="); sb.append (connectHost.getText ().toString ().trim ());
        sb.append ("&connectPort="); sb.append (connectPort.getText ().toString ().trim ());
        return sb.toString ();
    }

    /**
     * Convert string from a database back in to the parameters.
     */
    public void deserialize (String sb)
    {
        sb = "&" + sb + "&";

        int i = sb.indexOf ("&listenLocal=") + 13;
        int j = sb.indexOf ('&', i);
        boolean listenlocal = Boolean.parseBoolean (sb.substring (i, j));
        listenLocal.setChecked (listenlocal);
        listenRemote.setChecked (!listenlocal);
        connectLocal.setChecked (!listenlocal);
        connectRemote.setChecked (listenlocal);

        i = sb.indexOf ("&listenPort=") + 12;
        j = sb.indexOf ('&', i);
        listenPort.setText (sb.substring (i, j));

        i = sb.indexOf ("&connectHost=") + 13;
        j = sb.indexOf ('&', i);
        connectHost.setText (sb.substring (i, j));

        i = sb.indexOf ("&connectPort=") + 13;
        j = sb.indexOf ('&', i);
        connectPort.setText (sb.substring (i, j));
    }

    /**
     * Return whether or not the tunnel is open.
     */
    public boolean isOpen ()
    {
        try {
            int    lisport = Integer.parseInt (listenPort.getText ().toString ());
            int    conport = Integer.parseInt (connectPort.getText ().toString ());
            String conhost = connectHost.getText ().toString ().trim ().toLowerCase ();
            String fwdmsg  = ((lisport == 0) ? "" : lisport) + ":" + conhost + ":" + conport;

            String[] tuns = (listenLocal.isChecked ()) ? jsession.getPortForwardingL () : jsession.getPortForwardingR ();
            for (String tun : tuns) {
                tun = tun.toLowerCase ();
                if (lisport != 0) {
                    if (tun.equals (fwdmsg)) return true;
                } else if (tun.endsWith (fwdmsg)) {
                    actualPort = Integer.parseInt (tun.substring (0, tun.length () - fwdmsg.length ()));
                    return true;
                }
            }
        } catch (JSchException je) {
        }
        return false;
    }

    /**
     * Open the tunnel.
     */
    public String openTunnel ()
    {
        if (isOpen ()) return "already open";

        /*
         * Validate parameters.
         */
        String err = validate ();
        if (!err.equals ("")) return err;

        /*
         * Get parameters.
         */
        int    listenport  = Integer.parseInt (listenPort.getText ().toString ().trim ());
        String connecthost = connectHost.getText ().toString ().trim ();
        int    connectport = Integer.parseInt (connectPort.getText ().toString ());

        /*
         * Try to open the connection.
         */
        if (listenLocal.isChecked ()) {
            try {
                actualPort = jsession.setPortForwardingL (listenport, connecthost, connectport);
            } catch (JSchException je) {
                err = SshClient.GetExMsg (je);
            }
        } else {
            try {
                actualPort = listenport;
                jsession.setPortForwardingR (listenport, connecthost, connectport);
            } catch (JSchException je) {
                err = SshClient.GetExMsg (je);
            }
        }

        return err;
    }

    /**
     * Close the tunnel.
     */
    public void closeTunnel ()
    {
        if (isOpen ()) {
            if (listenLocal.isChecked ()) {
                try { jsession.delPortForwardingL (actualPort); } catch (JSchException je) { }
            } else {
                try { jsession.delPortForwardingR (actualPort); } catch (JSchException je) { }
            }
        }
    }

    /**
     * Determine if anything is wrong with the values in the widgets.
     */
    public String validate ()
    {
        String err = "";

        if (!listenLocal.isChecked () && !listenRemote.isChecked ()) {
            err += "\nneither listen local nor listen remote checked";
        }

        try {
            int lowlim = listenRemote.isChecked () ? 1 : 0;
            int listenport = Integer.parseInt (listenPort.getText ().toString ().trim ());
            if ((listenport < lowlim) || (listenport > 65535)) {
                throw new NumberFormatException ("out of range " + lowlim + "..65535");
            }
        } catch (NumberFormatException nfe) {
            err += "\nbad listen port number: " + SshClient.GetExMsg (nfe);
        }

        if (!connectLocal.isChecked () && !connectRemote.isChecked ()) {
            err += "\nneither connect local not connect remote checked";
        }

        String connecthost = connectHost.getText ().toString ().trim ();
        if (connecthost.equals ("")) {
            err += "\nconnect host not filled in";
        }

        try {
            int connectport = Integer.parseInt (connectPort.getText ().toString ());
            if ((connectport < 1) || (connectport > 65535)) throw new NumberFormatException ("out of range 1..65535");
        } catch (NumberFormatException nfe) {
            err += "\nbad connect port number: " + SshClient.GetExMsg (nfe);
        }

        return err.trim ();
    }
}
