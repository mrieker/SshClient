/**
 * Maintain list of username@hostname[:portnumber] we know about in a file.
 * The key is the case-insensitive username@hostname[:portnumber] string.
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
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;
import java.util.TreeMap;

public class SavedLogins extends TreeMap<String,SavedLogin> {
    public static final String TAG = "SshClient";

    private AlertDialog currentMenuDialog;
    private ArrayAdapter<String> autocompleteadapter;
    private File filename;
    private SshClient sshclient;

    public SavedLogins (SshClient sc)
    {
        sshclient = sc;

        filename = new File (sshclient.getFilesDir (), "saved_logins.enc");
        if (filename.exists ()) {
            try {
                BufferedReader rdr = new BufferedReader (sshclient.getMasterPassword ().EncryptedFileReader (filename.getPath ()), 4096);
                try {
                    String rec;
                    while ((rec = rdr.readLine ()) != null) {
                        SavedLogin sh = new SavedLogin (rec);
                        put (sh.getUserAtHost (), sh);
                    }
                } finally {
                    rdr.close ();
                }
            } catch (Exception e) {
                Log.e (TAG, "error reading " + filename.getPath (), e);
                sshclient.ErrorAlert ("Error reading saved logins", SshClient.GetExMsg (e));
            }
        }
    }

    public String GetFileName ()
    {
        return filename.getPath ();
    }

    /**
     * Add an entry to the list.
     */
    public void put (SavedLogin sh)
    {
        put (sh.getUserAtHost(), sh);
    }

    /**
     * Display menu of saved logins and allow user to click on one to delete.
     */
    public void ShowSavedLoginsMenu ()
    {
        final AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Saved Logins");
        ab.setMessage ("username@hostname[:portnumber] saved for autocomplete\n- click to delete");

        LinearLayout llv = new LinearLayout (sshclient);
        llv.setOrientation (LinearLayout.VERTICAL);

        View.OnClickListener butlis = new View.OnClickListener () {
            @Override
            public void onClick (View view) {
                currentMenuDialog.dismiss ();
                AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
                final String sh = (String)view.getTag ();
                ab.setTitle ("Confirm delete");
                ab.setMessage (sh);
                ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                    @Override
                    public void onClick (DialogInterface dialog, int whichButton)
                    {
                        remove (sh);
                        SaveChanges ();
                    }
                });
                ab.setNegativeButton ("Cancel", null);
                ab.show ();
            }
        };

        for (String sh : keySet ()) {
            Button but = sshclient.MyButton ();
            but.setTag (sh);
            but.setText (sh + get (sh).getSummary ());
            but.setOnClickListener (butlis);
            llv.addView (but);
        }

        ScrollView sv = new ScrollView (sshclient);
        sv.addView (llv);

        ab.setView (sv);
        ab.setNegativeButton ("Cancel", null);
        currentMenuDialog = ab.show ();
    }

    /**
     * Write the in-memory list out to the datafile.
     * Also update the names available to all hostnametext input boxes.
     */
    public void SaveChanges ()
    {
        File tempname = new File (filename.getPath () + ".tmp");
        try {
            PrintWriter wtr = new PrintWriter (sshclient.getMasterPassword ().EncryptedFileWriter (tempname.getPath ()));
            try {
                for (SavedLogin sh : values ()) {
                    wtr.println (sh.getRecord ());
                }
            } finally {
                wtr.close ();
            }
            if (!tempname.renameTo (filename)) {
                throw new IOException ("rename failed");
            }
        } catch (Exception e) {
            Log.e (TAG, "error writing " + filename.getPath (), e);
            sshclient.ErrorAlert ("Error writing saved logins", SshClient.GetExMsg (e));
        }
        autocompleteadapter = null;
        for (MySession s : sshclient.getAllsessions ()) {
            s.getHostnametext ().setAdapter (GetAutoCompleteAdapter ());
        }
    }

    public ArrayAdapter<String> GetAutoCompleteAdapter ()
    {
        if (autocompleteadapter == null) {
            Set<String> keys = keySet ();
            String[] array = new String[keys.size()];
            array = keys.toArray (array);
            autocompleteadapter = new ArrayAdapter<String> (
                    sshclient,
                    android.R.layout.simple_dropdown_item_1line,
                    array
            );
        }
        return autocompleteadapter;
    }
}
