/**
 * Keep track of hosts that we know the fingerprint of.
 * Just like com.jcraft.jsch.KnownHosts except file is encrypted.
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.UserInfo;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;


public class MyHostKeyRepo implements HostKeyRepository {
    public static final String TAG = "SshClient";

    private final TreeMap<String,ArrayList<HostKey>> pool;

    private AlertDialog currentMenuDialog;
    private SshClient sshclient;
    private String knownhostsfilename;

    public MyHostKeyRepo (SshClient sc)
    {
        sshclient = sc;
        knownhostsfilename = sc.getKnownhostsfilename ();
        pool = new TreeMap<String,ArrayList<HostKey>> ();
        ReadExistingFile ();
    }

    /**
     * Checks if <code>host</code> is included with the <code>key</code>.
     *
     * @return #NOT_INCLUDED, #OK or #CHANGED
     * @see #NOT_INCLUDED
     * @see #OK
     * @see #CHANGED
     */
    @Override // HostKeyRepository
    public int check (String host, byte[] key)
    {
        int rc = HostKeyRepository.NOT_INCLUDED;
        synchronized (pool) {
            if (pool.containsKey (host)) {
                List<HostKey> hks = pool.get (host);
                for (HostKey hk : hks) {
                    if (!hk.getHost ().equals (host)) continue;
                    if (Arrays.equals (KeyStr2Bin (hk.getKey ()), key)) {
                        return HostKeyRepository.OK;
                    }
                    rc = HostKeyRepository.CHANGED;
                }
            }
        }
        return rc;
    }

    /**
     * Adds a host key <code>hostkey</code>
     *
     * @param hostkey a host key to be added
     * @param ui a user interface for showing messages or promping inputs.
     * @see com.jcraft.jsch.UserInfo
     */
    @Override // HostKeyRepository
    public void add (HostKey hostkey, UserInfo ui)
    {
        String host = hostkey.getHost ();
        synchronized (pool) {
            if (!pool.containsKey (host)) {
                pool.put (host, new ArrayList<HostKey> ());
            }
            ArrayList<HostKey> list = pool.get (host);
            list.add (hostkey);
            RewriteFile ();
        }
    }

    /**
     * Removes a host key if there exists mached key with
     * <code>host</code>, <code>type</code>.
     *
     * @see #remove(String host, String type, byte[] key)
     */
    @Override // HostKeyRepository
    public void remove (String host, String type)
    {
        synchronized (pool) {
            if (pool.containsKey (host)) {
                boolean rewrite = false;
                ArrayList<HostKey> hks = pool.get (host);
                for (Iterator<HostKey> it = hks.iterator (); it.hasNext ();) {
                    HostKey hk = it.next ();
                    if (hk.getType ().equals (type)) {
                        it.remove ();
                        rewrite = true;
                    }
                }
                if (rewrite) RewriteFile ();
            }
        }
    }

    /**
     * Removes a host key if there exists a matched key with
     * <code>host</code>, <code>type</code> and <code>key</code>.
     */
    @Override // HostKeyRepository
    public void remove (String host, String type, byte[] key)
    {
        synchronized (pool) {
            if (pool.containsKey (host)) {
                boolean rewrite = false;
                List<HostKey> hks = pool.get (host);
                for (Iterator<HostKey> it = hks.iterator (); it.hasNext ();) {
                    HostKey hk = it.next ();
                    if (hk.getType ().equals (type) &&
                            Arrays.equals (KeyStr2Bin (hk.getKey ()), key)) {
                        it.remove ();
                        rewrite = true;
                    }
                }
                if (rewrite) RewriteFile ();
            }
        }
    }

    /**
     * Remove a specific HostKey entry from list of known hosts.
     */
    public void remove (HostKey hk)
    {
        String host = hk.getHost ();
        synchronized (pool) {
            if (pool.containsKey (host)) {
                List<HostKey> hks = pool.get (host);
                for (Iterator<HostKey> it = hks.iterator (); it.hasNext ();) {
                    if (it.next () == hk) {
                        it.remove ();
                        RewriteFile ();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Returns id of this repository.
     *
     * @return identity in String
     */
    @Override // HostKeyRepository
    public String getKnownHostsRepositoryID ()
    {
        return knownhostsfilename;
    }

    /**
     * Retuns a list for host keys managed in this repository.
     *
     * @see #getHostKey(String host, String type)
     */
    @Override // HostKeyRepository
    public HostKey[] getHostKey ()
    {
        synchronized (pool) {
            int i = 0;
            for (List<HostKey> hks : pool.values ()) {
                i += hks.size ();
            }
            HostKey[] array = new HostKey[i];
            i = 0;
            for (List<HostKey> hks : pool.values ()) {
                for (HostKey hk : hks) array[i++] = hk;
            }
            return array;
        }
    }

    /**
     * Returns a list for host keys managed in this repository.
     *
     * @param host a hostname used in searching host keys.
     *        If <code>null</code> is given, every host key will be listed.
     * @param type a key type used in searching host keys,
     *        and it should be "ssh-dss" or "ssh-rsa".
     *        If <code>null</code> is given, a key type type will not be ignored.
     */
    @Override // HostKeyRepository
    public HostKey[] getHostKey (String host, String type)
    {
        synchronized (pool) {
            HostKey[] array;
            if (host == null) {
                int i = 0;
                for (List<HostKey> hks : pool.values ()) {
                    for (HostKey hk : hks) {
                        if ((type == null) || hk.getType ().equals (type)) i ++;
                    }
                }
                array = new HostKey[i];
                i = 0;
                for (List<HostKey> hks : pool.values ()) {
                    for (HostKey hk : hks) {
                        if ((type == null) || hk.getType ().equals (type)) array[i++] = hk;
                    }
                }
            } else if (pool.containsKey (host)) {
                List<HostKey> hks = pool.get (host);
                int i = 0;
                for (HostKey hk : hks) {
                    if ((type == null) || hk.getType ().equals (type)) i ++;
                }
                array = new HostKey[i];
                i = 0;
                for (HostKey hk : hks) {
                    if ((type == null) || hk.getType ().equals (type)) array[i++] = hk;
                }
            } else {
                array = new HostKey[0];
            }
            return array;
        }
    }

    /**
     * Display menu of known hosts and allow user to click on one to delete.
     */
    public void ShowKnownHostMenu ()
    {
        final JSch jsch = new JSch ();
        jsch.setHostKeyRepository (this);

        AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Known Hosts");
        ab.setMessage ("hosts for which fingerprints are known\n- click to delete");

        LinearLayout llv = new LinearLayout (sshclient);
        llv.setOrientation (LinearLayout.VERTICAL);

        // listener so when button is clicked, dialog is dismissed and known host is deleted
        View.OnClickListener butlis = new View.OnClickListener () {
            @Override
            public void onClick (View view) {
                currentMenuDialog.dismiss ();
                AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
                final HostKey hk = (HostKey) view.getTag ();
                ab.setTitle ("Confirm delete");
                ab.setMessage (hk.getHost () + " [" + hk.getType () + "] " + hk.getFingerPrint (jsch));
                ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                   @Override
                   public void onClick (DialogInterface dialog, int whichButton)
                   {
                       remove (hk);
                   }
                });
                ab.setNegativeButton ("Cancel", null);
                ab.show ();
            }
        };

        // populate the list by reading the known hosts file
        // it is written by the JSch package when a hosts fingerprint is accepted by the user
        // the first word (up to a space) is the host name
        HostKey[] hks = getHostKey ();
        for (HostKey hk : hks) {
            Button but = sshclient.MyButton ();
            but.setTag (hk);
            but.setText (hk.getHost () + " [" + hk.getType () + "] " + hk.getFingerPrint (jsch));
            but.setOnClickListener (butlis);
            llv.addView (but);
        }

        // display menu with a Cancel button
        ScrollView sv = new ScrollView (sshclient);
        sv.addView (llv);

        ab.setView (sv);
        ab.setNegativeButton ("Cancel", null);
        currentMenuDialog = ab.show ();
    }

    /**
     * Read through existing known hosts file and put entries in the pool.
     */
    private void ReadExistingFile ()
    {
        try {
            BufferedReader rdr = new BufferedReader (sshclient.getMasterPassword ().EncryptedFileReader (knownhostsfilename), 4096);
            try {
                String rec;
                while ((rec = rdr.readLine ()) != null) {
                    int i = rec.indexOf (' ');
                    String host = rec.substring (0, i);
                    int j = rec.indexOf (' ', ++ i);
                    String type = rec.substring (i, j);
                    int typebin = TypeStr2Bin (type);
                    String keystr = rec.substring (++ j);
                    byte[] keybin = KeyStr2Bin (keystr);
                    HostKey hk = new HostKey (host, typebin, keybin);
                    add (hk, null);
                }
            } finally {
                rdr.close ();
            }
        } catch (FileNotFoundException fnfe) {
        } catch (Exception e) {
            Log.e (TAG, "error reading " + knownhostsfilename, e);
            sshclient.ErrorAlert ("Error reading known hosts", SshClient.GetExMsg (e));
        }
    }

    /**
     * (Re-)write the known hosts file by writing all entries to a temp file
     * then renaming the temp file over any permanent file that might be there.
     */
    private void RewriteFile ()
    {
        try {
            PrintWriter wtr = new PrintWriter (sshclient.getMasterPassword ().EncryptedFileWriter (knownhostsfilename + ".tmp"));
            for (String host : pool.keySet ()) {
                for (HostKey hk : pool.get (host)) {
                    String type = hk.getType ();
                    String key  = hk.getKey ();

                    // see if ReadExistingFile() stands a chance of converting it back
                    if (host.indexOf (' ') >= 0) throw new Exception ("host <" + host + "> contains space");
                    if (type.indexOf (' ') >= 0) throw new Exception ("type <" + type + "> contains space");
                    int typebin = TypeStr2Bin (type);
                    byte[] keybin = KeyStr2Bin (key);
                    HostKey tmp = new HostKey (host, typebin, keybin);
                    if (!tmp.getType ().equals (type)) throw new Exception ("type verify error");
                    if (!tmp.getKey ().equals (key)) throw new Exception ("key verify error");

                    // we should be able to convert it back, write to file
                    wtr.println (host + " " + type + " " + key);
                }
            }
            wtr.close ();
            MasterPassword.RenameTempToPerm (knownhostsfilename);
        } catch (Exception e) {
            Log.e (TAG, "error writing " + knownhostsfilename, e);
            sshclient.ErrorAlert ("Error writing known hosts", SshClient.GetExMsg (e));
        }
    }

    /**
     * Convert string type returned by HostKey.getType()
     * to the binary value required by new HostKey().
     */
    private int TypeStr2Bin (String type)
    {
        int hkt = 0;
        if (type.equals ("ssh-dss")) hkt = HostKey.SSHDSS;
        if (type.equals ("ssh-rsa")) hkt = HostKey.SSHRSA;
        if (hkt == 0) throw new RuntimeException ("bad keytype " + type);
        return hkt;
    }

    /**
     * Convert string key returned by HostKey.getKey()
     * to the binary value required by new HostKey().
     */
    private byte[] KeyStr2Bin (String keystr)
    {
        int keystrlen  = keystr.length ();
        char[] keychrs = keystr.toCharArray ();
        int j = 0;
        byte[] tmpbin = new byte[keystrlen];
        for (int i = 0; i < keystrlen;) {
            int chr0 = SixBits (keychrs[i++]);
            int chr1 = SixBits (keychrs[i++]);
            if ((chr0 | chr1) < 0) throw new RuntimeException ("bad keystring chars");
            tmpbin[j++] = (byte)((chr0 << 2) | ((chr1 & 0x30) >>> 4));
            int chr2 = SixBits (keychrs[i++]);
            if (chr2 < 0) break;
            tmpbin[j++] = (byte)(((chr1 & 0x0F) << 4) | ((chr2 & 0x3C) >>> 2));
            int chr3 = SixBits (keychrs[i++]);
            if (chr3 < 0) break;
            tmpbin[j++] = (byte)(((chr2 & 0x03) << 6) | (chr3 & 0x3F));
        }
        byte[] keybin = new byte[j];
        System.arraycopy (tmpbin, 0, keybin, 0, j);
        return keybin;
    }

    private int SixBits (char chr) throws RuntimeException
    {
        int i = "=ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".indexOf (chr);
        if (i < 0) throw new RuntimeException ("bad keystring char");
        return -- i;
    }
}