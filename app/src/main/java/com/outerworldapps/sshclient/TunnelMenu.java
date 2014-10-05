/**
 * Menu to edit tunnel database.
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
import android.content.DialogInterface;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jcraft.jsch.Session;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedList;

public class TunnelMenu {
    public final static String TAG = "SshClient";

    public final static int colorOpen = Color.RED;
    public final static int colorClosed = Color.BLACK;

    private AlertDialog currentAlertDialog;
    private SshClient sshclient;                // GUI screen
    private String key;                         // "user@host:port"
    private String prefix;                      // "user@host:port/"
    private String tunnelFileName;
    private HashMap<String,LinkedList<TunnelView>> tunnelLists;  // all tunnels
    LinkedList<TunnelView> tunnelList;          // tunnels for the current key

    public String getTunnelFileName () { return tunnelFileName; }

    public TunnelMenu (SshClient sc)
    {
        sshclient = sc;
        tunnelFileName = new File (sshclient.getFilesDir (), "tunnels.enc").getAbsolutePath ();
        tunnelLists = new HashMap<String,LinkedList<TunnelView>> ();
    }

    /**
     * Set current user@host:port key and build menu for editing tunnel database.
     */
    public View getMenu (final Session jses)
    {
        /*
         * Read tunnel definitions for the user@host:port into memory if not already there.
         */
        setCurrentKey (jses);

        /*
         * Create a menu of the existing tunnels for this key of user@host:port.
         */
        final LinearLayout llv = new LinearLayout (sshclient);
        llv.setOrientation (LinearLayout.VERTICAL);

        TextView tv1 = sshclient.MyTextView ();
        tv1.setText ("Tunnels for " + key);
        llv.addView (tv1);

        TextView tv2 = sshclient.MyTextView ();
        tv2.setText ("- short click to open/close/edit\n- long click to delete");
        llv.addView (tv2);

        /*
         * Set up a button for each existing entry for this user@host:port.
         */
        for (final TunnelView tv : tunnelList) {
            addTunnelButton (llv, tv);
        }

        /*
         * Set up a button to create a new entry.
         */
        final Button but = sshclient.MyButton ();
        but.setText ("Create");
        but.setOnClickListener (new View.OnClickListener () {
            @Override
            public void onClick (View view)
            {
                createNewTunnel (llv, but, jses);
            }
        });
        llv.addView (but);

        /*
         * Wrap it all in a vertical scroll in case of small screen.
         */
        ScrollView sv = new ScrollView (sshclient);
        sv.addView (llv);
        return sv;
    }

    /**
     * Display dialog that creates a new tunnel database entry for the current user@host:port.
     */
    private void createNewTunnel (final LinearLayout llv, final Button crebut, Session jses)
    {
        final TunnelView newTunnelView = new TunnelView (sshclient, jses);
        AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Create new tunnel to " + key);
        ab.setView (newTunnelView);
        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
            @Override
            public void onClick (DialogInterface dialogInterface, int i)
            {
                String err = newTunnelView.validate ();
                if (err.equals ("")) {
                    tunnelList.addLast (newTunnelView);
                    writeTunnelFile ();
                    llv.removeView (crebut);
                    addTunnelButton (llv, newTunnelView);
                    llv.addView (crebut);
                } else {
                    AlertDialog.Builder abe = new AlertDialog.Builder (sshclient);
                    abe.setTitle ("Error creating new tunnel");
                    abe.setMessage (err);
                    abe.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                        @Override
                        public void onClick (DialogInterface dialogInterface, int i) {
                            currentAlertDialog.show ();
                        }
                    });
                    abe.setNegativeButton ("Cancel", null);
                    abe.show ();
                }
            }
        });
        ab.setNegativeButton ("Cancel", null);
        currentAlertDialog = ab.show ();
    }

    /**
     * Make a button to select an existing tunnel definition.
     */
    private void addTunnelButton (final LinearLayout llv, final TunnelView tv)
    {
        final Button but = sshclient.MyButton ();
        but.setText (tv.getLabel ());
        but.setTextColor (tv.isOpen () ? colorOpen : colorClosed);

        /*
         * Short click opens/edits/closes the tunnel.
         */
        but.setOnClickListener (new View.OnClickListener () {
            @Override
            public void onClick (View view)
            {
                /*
                 * Display dialog asking them what they want to do.
                 */
                final boolean isopen = tv.isOpen ();
                AlertDialog.Builder abc = new AlertDialog.Builder (sshclient);
                abc.setTitle ((isopen ? "Close" : "Open") + " tunnel to " + key);
                abc.setMessage (tv.getLabel ());

                /*
                 * OK button to confirm opening/closing connection.
                 */
                abc.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                    @Override
                    public void onClick (DialogInterface dialogInterface, int i)
                    {
                        if (isopen) {
                            tv.closeTunnel ();
                        } else {
                            String err = tv.openTunnel ();
                            if (!err.equals ("")) {
                                AlertDialog.Builder abe = new AlertDialog.Builder (sshclient);
                                abe.setTitle ("Error opening tunnel");
                                abe.setMessage (err);
                                abe.setPositiveButton ("OK", null);
                                abe.show ();
                            }
                        }
                        but.setTextColor (tv.isOpen () ? colorOpen : colorClosed);
                        but.setText (tv.getLabel ());  // maybe have ephemeral port number now
                    }
                });

                /*
                 * Edit button to edit a closed connection.
                 */
                if (!isopen) {
                    abc.setNeutralButton ("Edit", new DialogInterface.OnClickListener () {
                        @Override
                        public void onClick (DialogInterface dialogInterface, int i)
                        {
                            if (tv.editDialog == null) {
                                final String original = tv.serialize ();
                                AlertDialog.Builder abe = new AlertDialog.Builder (sshclient);
                                abe.setTitle ("Edit tunnel to " + key);
                                ScrollView sve = new ScrollView (sshclient);
                                sve.addView (tv);
                                abe.setView (sve);

                                /*
                                 * Saving the changes writes the new values to database.
                                 */
                                abe.setPositiveButton ("Save", new DialogInterface.OnClickListener () {
                                    @Override
                                    public void onClick (DialogInterface dialogInterface, int i)
                                    {
                                        String err = tv.validate ();
                                        if (err.equals ("")) {
                                            writeTunnelFile ();
                                            but.setText (tv.getLabel ());
                                        } else {
                                            AlertDialog.Builder abr = new AlertDialog.Builder (sshclient);
                                            abr.setTitle ("Error saving new tunnel");
                                            abr.setMessage (err);
                                            abr.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                                                @Override
                                                public void onClick (DialogInterface dialogInterface, int i) {
                                                    tv.editDialog.show ();
                                                }
                                            });
                                            abr.setOnCancelListener (new DialogInterface.OnCancelListener () {
                                                @Override
                                                public void onCancel (DialogInterface dialogInterface)
                                                {
                                                    tv.deserialize (original);
                                                }
                                            });
                                            abr.show ();
                                        }
                                    }
                                });

                                /*
                                 * Cancelling the changes restores values to original values.
                                 */
                                abe.setNegativeButton ("Cancel", new DialogInterface.OnClickListener () {
                                    @Override
                                    public void onClick (DialogInterface dialogInterface, int i)
                                    {
                                        tv.deserialize (original);
                                    }
                                });

                                abe.setOnCancelListener (new DialogInterface.OnCancelListener () {
                                    @Override
                                    public void onCancel (DialogInterface dialogInterface)
                                    {
                                        tv.deserialize (original);
                                    }
                                });

                                tv.editDialog = abe.show ();
                            } else {
                                tv.editDialog.show ();
                            }
                        }
                    });
                }

                abc.setNegativeButton ("Cancel", null);
                abc.show ();
            }
        });

        /*
         * Long click deletes the tunnel from the database.
         */
        but.setOnLongClickListener (new View.OnLongClickListener () {
            @Override
            public boolean onLongClick (View view) {
                AlertDialog.Builder abc = new AlertDialog.Builder (sshclient);
                abc.setTitle ("Delete tunnel to " + key);
                abc.setMessage (tv.getLabel ());
                abc.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                    @Override
                    public void onClick (DialogInterface dialogInterface, int i)
                    {
                        if (tv.isOpen ()) tv.closeTunnel ();
                        tunnelList.remove (tv);
                        writeTunnelFile ();
                        llv.removeView (but);
                    }
                });
                abc.setNegativeButton ("Cancel", null);
                abc.show ();
                return true;
            }
        });

        /*
         * Add to end of list of buttons.
         */
        llv.addView (but);
    }

    /**
     * Set up the given connection as the current key and read database if not already in memory.
     */
    private void setCurrentKey (Session jses)
    {
        key = jses.getUserName () + "@" + jses.getHost () + ":" + jses.getPort ();
        prefix = key + "/";

        /*
         * Read existing tunnel definitions for the key from datafile into memory if we don't already have it.
         */
        tunnelList = tunnelLists.get (key);
        if (tunnelList == null) {
            tunnelList = new LinkedList<TunnelView> ();
            try {
                BufferedReader br = new BufferedReader (sshclient.getMasterPassword ().EncryptedFileReader (tunnelFileName));
                try {
                    String line;
                    while ((line = br.readLine ()) != null) {
                        if (line.startsWith (prefix)) {
                            TunnelView tv = new TunnelView (sshclient, jses);
                            tv.deserialize (line.substring (prefix.length ()));
                            tunnelList.addLast (tv);
                        }
                    }
                } finally {
                    br.close ();
                }
            } catch (FileNotFoundException fnfe) {
            } catch (Exception e) {
                Log.e (TAG, "error reading " + tunnelFileName, e);
                sshclient.ErrorAlert ("Error reading tunnels", SshClient.GetExMsg (e));
                return;
            }
            tunnelLists.put (key, tunnelList);
        }
    }

    /**
     * Write in-memory tunnels for the current key to flash.
     */
    private void writeTunnelFile ()
    {
        MasterPassword mp = sshclient.getMasterPassword ();
        try {
            PrintWriter pw = new PrintWriter (mp.EncryptedFileWriter (tunnelFileName + ".tmp"));
            try {
                try {
                    BufferedReader br = new BufferedReader (mp.EncryptedFileReader (tunnelFileName));
                    try {
                        String line;
                        while ((line = br.readLine ()) != null) {
                            if (!line.startsWith (prefix)) {
                                pw.println (line);
                            }
                        }
                    } finally {
                        br.close ();
                    }
                } catch (FileNotFoundException fnfe) {
                }
                for (TunnelView tv : tunnelList) {
                    pw.println (prefix + tv.serialize ());
                }
                pw.flush ();
            } finally {
                pw.close ();
            }
            MasterPassword.RenameTempToPerm (tunnelFileName);
        } catch (Exception e) {
            Log.e (TAG, "error reading " + tunnelFileName, e);
            sshclient.ErrorAlert ("Error reading tunnels", SshClient.GetExMsg (e));
        }
    }
}
