/**
 * Menu for manging local key pair files.
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


import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.ClipboardManager;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

import java.io.BufferedReader;
import java.io.File;
import java.io.OutputStream;
import java.util.TreeMap;

@SuppressLint("SetTextI18n")
public class LocalKeyPairMenu {
    public static final String TAG = "SshClient";

    public static final String IDENT_CHARS = "01234567890ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz@._-";
    public static final int IDENT_MAXSZ = 50;

    private AlertDialog currentMenuDialog;
    private SshClient sshclient;

    public LocalKeyPairMenu (SshClient sc)
    {
        sshclient = sc;
    }

    /**
     * Display a menu that manipulates the local key pair files.
     */
    public void ShowLocalKeyPairMenu ()
    {
        final AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Local Keypairs");
        ab.setMessage ("identifies this client to remote hosts");

        LinearLayout llv = new LinearLayout (sshclient);
        llv.setOrientation (LinearLayout.VERTICAL);

        /*
         * Generate list of buttons for existing keypair files.
         */
        TreeMap<String,String> existing = GetExistingKeypairIdents ();

        View.OnClickListener butlis = new View.OnClickListener () {
            @Override
            public void onClick (View view) {
                currentMenuDialog.dismiss ();
                ShowExistingKeypairMenu (((Button) view).getText().toString ());
            }
        };

        for (String ident : existing.keySet ()) {
            Button existbut = sshclient.MyButton ();
            existbut.setText (ident);
            existbut.setOnClickListener (butlis);
            llv.addView (existbut);
        }

        /*
         * Show buttons to generate new keypair files.
         */
        Button gen = sshclient.MyButton ();
        gen.setText ("generate new keypair");
        gen.setOnClickListener (new View.OnClickListener () {
            public void onClick (View v) {
                currentMenuDialog.dismiss ();
                GenerateNewKeyPair ();
            }
        });
        llv.addView (gen);

        Button lod = sshclient.MyButton ();
        lod.setText ("load keypair directly");
        lod.setOnClickListener (new View.OnClickListener () {
            public void onClick (View v) {
                currentMenuDialog.dismiss ();
                LoadKeyPairDirectly ();
            }
        });
        llv.addView (lod);

        ScrollView sv = new ScrollView (sshclient);
        sv.addView (llv);
        ab.setView (sv);

        ab.setNegativeButton ("Cancel", null);
        currentMenuDialog = ab.show ();
    }

    /**
     * Get list of existing keypair idents by scanning the directory.
     * Assumes the privatekeywildname string has a single '*' in it.
     */
    public TreeMap<String,String> GetExistingKeypairIdents ()
    {
        File dir      = new File (sshclient.getPrivatekeyfilename ("*")).getParentFile ();
        File[] files  = dir.listFiles ();
        String wild   = new File (sshclient.getPrivatekeyfilename ("*")).getName ();
        int i = wild.indexOf ('*');
        String prefix = wild.substring (0, i);
        String suffix = wild.substring (++ i);
        TreeMap<String,String> matches = new TreeMap<String,String> ();
        for (i = 0; i < files.length; i ++) {
            String prvkey = files[i].getName ();
            if (prvkey.startsWith (prefix) && prvkey.endsWith (suffix)) {
                String ident = prvkey.substring (prefix.length (), prvkey.length () - suffix.length ());
                matches.put (ident, ident);
            }
        }
        return matches;
    }

    /**
     * Display menu to operate on the given existing keypair files.
     */
    private void ShowExistingKeypairMenu (final String ident)
    {
        final File privatekeyfile = new File (sshclient.getPrivatekeyfilename (ident));
        final File publickeyfile  = new File (sshclient.getPublickeyfilename (ident));

        String publickeyfingr;
        try {
            byte[] publickeybytes = sshclient.getMasterPassword ().ReadEncryptedFileBytes (publickeyfile.getAbsolutePath ());
            KeyPair kpair = KeyPair.load (new JSch (), null, publickeybytes);
            publickeyfingr = kpair.getFingerPrint ();
        } catch (Exception e) {
            publickeyfingr = SshClient.GetExMsg (e);
        }

        final AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Local Keypair");
        ab.setMessage (ident + "\n" + publickeyfingr);

        LinearLayout llv = new LinearLayout (sshclient);
        llv.setOrientation (LinearLayout.VERTICAL);

        /*
         * Display a button to delete the keypair files.
         */
        Button del = sshclient.MyButton ();
        del.setText ("delete exiting keypair\nclick twice so I'm sure");
        del.setOnClickListener (new View.OnClickListener () {
            private int count = 0;
            public void onClick (View v) {
                if (++ count >= 2) {
                    currentMenuDialog.dismiss ();
                    AlertDialog.Builder abd = new AlertDialog.Builder (sshclient);
                    abd.setTitle ("Delete existing keypair " + ident);
                    abd.setMessage ("Are you sure?");
                    abd.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                        @Override
                        public void onClick (DialogInterface dialogInterface, int i)
                        {
                            //noinspection ResultOfMethodCallIgnored
                            publickeyfile.delete ();
                            //noinspection ResultOfMethodCallIgnored
                            privatekeyfile.delete ();
                        }
                    });
                    abd.setNegativeButton ("Cancel", null);
                    abd.show ();
                }
            }
        });
        llv.addView (del);

        /*
         * Display a button to copy the public key to the clipboard.
         */
        Button xmi = sshclient.MyButton ();
        xmi.setText ("copy public key to internal clipboard");
        xmi.setOnClickListener (new View.OnClickListener () {
            public void onClick (View v)
            {
                currentMenuDialog.dismiss ();
                String pk = SendEncryptedKeyFileToClipboard (publickeyfile.getPath ());
                if (pk != null) sshclient.internalClipboard = pk;
            }
        });
        llv.addView (xmi);

        Button xmx = sshclient.MyButton ();
        xmx.setText ("copy public key to external clipboard");
        xmx.setOnClickListener (new View.OnClickListener () {
            public void onClick (View v)
            {
                currentMenuDialog.dismiss ();
                String pk = SendEncryptedKeyFileToClipboard (publickeyfile.getPath ());
                if (pk != null) {
                    ClipboardManager cbm = (ClipboardManager)sshclient.getSystemService (Context.CLIPBOARD_SERVICE);
                    cbm.setText (pk);
                }
            }
        });
        llv.addView (xmx);

        /*
         * Display a button to copy the private key to the clipboard.
         */
        Button bak = sshclient.MyButton ();
        bak.setText ("copy private key to internal clipboard\nclick twice so I'm sure");
        bak.setOnClickListener (new View.OnClickListener () {
            private int count = 0;

            public void onClick (View v) {
                if (++count >= 2) {
                    currentMenuDialog.dismiss ();
                    String pk = SendEncryptedKeyFileToClipboard (privatekeyfile.getPath ());
                    if (pk != null) sshclient.internalClipboard = pk;
                }
            }
        });
        llv.addView (bak);

        /*
         * Display menu with the usual Cancel button.
         */
        ScrollView sv = new ScrollView (sshclient);
        sv.addView (llv);
        ab.setView (sv);
        ab.setNegativeButton ("Cancel", null);
        currentMenuDialog = ab.show ();
    }

    /**
     * Generate a new pair of keys and store them in the files.
     */
    private void GenerateNewKeyPair ()
    {
        final AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Generate Keypair");

        LinearLayout llv = new LinearLayout (sshclient);
        llv.setOrientation (LinearLayout.VERTICAL);

        final EditText idet = sshclient.MyEditText ();
        idet.setSingleLine (true);
        idet.setHint ("Name to identify this keypair, eg, mytablet");
        idet.setInputType (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        llv.addView (idet);

        final EditText ppet = sshclient.MyEditText ();
        ppet.setSingleLine (true);
        ppet.setHint ("Passphrase or leave empty for none");
        ppet.setInputType (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        llv.addView (ppet);

        final EditText p2et = sshclient.MyEditText ();
        p2et.setSingleLine (true);
        p2et.setHint ("Same passphrase again");
        p2et.setInputType (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        llv.addView (p2et);

        final CheckBox ppcb = sshclient.MyCheckBox ();
        ppcb.setText ("passphrase entry visible");
        ppcb.setOnClickListener (new View.OnClickListener () {
            public void onClick (View v)
            {
                if (ppcb.isChecked ()) {
                    ppet.setInputType (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                    p2et.setInputType (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                } else {
                    ppet.setInputType (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    p2et.setInputType (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                }
            }
        });
        llv.addView (ppcb);

        RadioButton rb2a = sshclient.MyRadioButton ();
        rb2a.setText ("DSA  ");
        RadioButton rb2b = sshclient.MyRadioButton ();
        rb2b.setText ("RSA  ");
        final RadioGroup rg2 = new RadioGroup (sshclient);
        rg2.setLayoutParams (new LinearLayout.LayoutParams (ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0.0F));
        rg2.setOrientation (LinearLayout.HORIZONTAL);
        rg2.addView (rb2a, 0, new LinearLayout.LayoutParams (ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 1.0F));
        rg2.addView (rb2b, 1, new LinearLayout.LayoutParams (ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 1.0F));
        llv.addView (rg2);

        RadioButton rb3a = sshclient.MyRadioButton ();
        rb3a.setText ("1024  ");
        RadioButton rb3b = sshclient.MyRadioButton ();
        rb3b.setText ("2048  ");
        RadioButton rb3c = sshclient.MyRadioButton ();
        rb3c.setText ("4096  ");
        final RadioGroup rg3 = new RadioGroup (sshclient);
        rg3.setLayoutParams (new LinearLayout.LayoutParams (ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0.0F));
        rg3.setOrientation (LinearLayout.HORIZONTAL);
        rg3.addView (rb3a, 0, new LinearLayout.LayoutParams (ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 1.0F));
        rg3.addView (rb3b, 1, new LinearLayout.LayoutParams (ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 1.0F));
        rg3.addView (rb3c, 2, new LinearLayout.LayoutParams (ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 1.0F));
        rb3a.toggle ();  // 1024 by default
        llv.addView (rg3);

        ScrollView sv = new ScrollView (sshclient);
        sv.addView (llv);

        ab.setView (sv);

        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton)
            {
                final String ident = idet.getText ().toString ().trim ();
                final String pp = ppet.getText ().toString ();
                final String p2 = p2et.getText ().toString ();
                String prvkeyfn = sshclient.getPrivatekeyfilename (ident);
                String pubkeyfn = sshclient.getPublickeyfilename  (ident);

                try {
                    if (ident.equals ("")) throw new Exception ("identity is blank");
                    if (ident.length () > IDENT_MAXSZ) throw new Exception ("identity is too long, max " + IDENT_MAXSZ + " chars");
                    for (int i = 0; i < ident.length (); i ++) {
                        if (IDENT_CHARS.indexOf (ident.charAt (i)) < 0) {
                            throw new Exception ("bad char <" + ident.charAt (i) + "> in ident");
                        }
                    }
                    if (new File (prvkeyfn).exists () && new File (pubkeyfn).exists ()) {
                        throw new Exception (ident + " already in use, use different name or delete existing keypair first");
                    }

                    if (!pp.equals (p2)) throw new Exception ("passphrase mismatch");

                    String trb = ((RadioButton)rg2.findViewById (rg2.getCheckedRadioButtonId ())).getText ().toString ();
                    int type = KeyPair.UNKNOWN;
                    if (trb.startsWith ("DSA")) type = KeyPair.DSA;
                    if (trb.startsWith ("RSA")) type = KeyPair.RSA;
                    if (type == KeyPair.UNKNOWN) throw new Exception ("no key format selected");

                    String srb = ((RadioButton)rg3.findViewById (rg3.getCheckedRadioButtonId ())).getText ().toString ();
                    int size = Integer.parseInt (srb.trim ());
                    if (size == 0) throw new Exception ("no key size selected");

                    // http://www.jcraft.com/jsch/examples/KeyGen.java.html
                    KeyPair kpair = KeyPair.genKeyPair (new JSch (), type, size);
                    OutputStream prvkey = sshclient.getMasterPassword ().EncryptedFileOutputStream (prvkeyfn);
                    OutputStream pubkey = sshclient.getMasterPassword ().EncryptedFileOutputStream (pubkeyfn);
                    byte[] ppbytes = null;
                    if (pp.length () > 0) ppbytes = pp.getBytes ("UTF-8");
                    kpair.writePrivateKey (prvkey, ppbytes);
                    kpair.writePublicKey  (pubkey, ident);
                    prvkey.close ();
                    pubkey.close ();

                    sshclient.ErrorAlert ("Keypair name and fingerprint", ident + "\n" + kpair.getFingerPrint ());
                    kpair.dispose ();
                } catch (Exception e) {
                    Log.e (TAG, "error generating keypair", e);
                    //noinspection ResultOfMethodCallIgnored
                    new File (prvkeyfn).delete ();
                    //noinspection ResultOfMethodCallIgnored
                    new File (pubkeyfn).delete ();
                    AlertDialog.Builder abe = new AlertDialog.Builder (sshclient);
                    abe.setTitle ("Keypair generation error");
                    abe.setMessage (SshClient.GetExMsg (e));
                    abe.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                        public void onClick (DialogInterface dialog, int whichButton) {
                            currentMenuDialog.show ();
                        }
                    });
                    abe.setNegativeButton ("Cancel", null);
                    abe.show ();
                }
            }
        });

        ab.setNegativeButton ("Cancel", null);

        currentMenuDialog = ab.show ();
    }

    /**
     * Load key pair files directly from clipboard.
     */
    private void LoadKeyPairDirectly ()
    {
        final AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Load Keypair Directly");
        ab.setMessage ("...from a clipboard\n" +
                "- the public key is the first non-blank line\n" +
                "- the private key is the rest of the text");

        LinearLayout llv = new LinearLayout (sshclient);
        llv.setOrientation (LinearLayout.VERTICAL);

        final TextView pairtxt = sshclient.MyTextView ();

        final Button pairint = sshclient.MyButton ();
        pairint.setText ("Load from internal clipboard");
        pairint.setOnClickListener (new View.OnClickListener () {
            @Override
            public void onClick (View v) {
                pairtxt.setTag (sshclient.internalClipboard);
                pairtxt.setText (AbbreviateKey (sshclient.internalClipboard));
                sshclient.internalClipboard = "";
            }
        });
        llv.addView (pairint);

        final Button pairext = sshclient.MyButton ();
        pairext.setText ("Load from external clipboard");
        pairext.setOnClickListener (new View.OnClickListener () {
            @Override
            public void onClick (View v)
            {
                ClipboardManager cbm = (ClipboardManager)sshclient.getSystemService (Context.CLIPBOARD_SERVICE);
                String keys = cbm.getText ().toString ().trim ();
                cbm.setText ("");
                pairtxt.setTag (keys);
                pairtxt.setText (AbbreviateKey (keys));
            }
        });
        llv.addView (pairext);

        llv.addView (pairtxt);

        ScrollView sv = new ScrollView (sshclient);
        sv.addView (llv);

        ab.setView (sv);

        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener() {
            public void onClick (DialogInterface dialog, int whichButton)
            {
                String keys = (String) pairtxt.getTag ();
                try {
                    int i = keys.indexOf ('\n');
                    if (i < 0) throw new Exception ("does not contain more than one line");
                    String pubstr = keys.substring (0, i).trim () + "\n";
                    String prvstr = keys.substring (++ i).trim () + "\n";
                    final byte[] pubbin = pubstr.getBytes ("UTF-8");
                    final byte[] prvbin = prvstr.getBytes ("UTF-8");
                    final KeyPair kpair = KeyPair.load (new JSch (), prvbin, pubbin);
                    String ident  = kpair.getPublicKeyComment ();
                    String prvfnm = sshclient.getPrivatekeyfilename (ident);
                    String pubfnm = sshclient.getPublickeyfilename (ident);
                    if (new File (prvfnm).exists () && new File (pubfnm).exists ()) {
                        AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
                        ab.setTitle ("Duplicate keypair");
                        ab.setMessage ("you already have a keypair named " + ident);
                        ab.setPositiveButton ("Overwrite", new DialogInterface.OnClickListener () {
                            @Override
                            public void onClick (DialogInterface dialogInterface, int i)
                            {
                                WriteKeypairFiles (kpair, prvbin, pubbin);
                            }
                        });
                        ab.setNegativeButton ("Cancel", null);
                        ab.show ();
                    } else {
                        WriteKeypairFiles (kpair, prvbin, pubbin);
                    }
                } catch (Exception e) {
                    Log.w (TAG, "error loading keypair", e);
                    AlertDialog.Builder abe = new AlertDialog.Builder (sshclient);
                    abe.setTitle ("Keypair load error");
                    abe.setMessage (SshClient.GetExMsg (e));
                    abe.setPositiveButton ("OK", new DialogInterface.OnClickListener ()
                    {
                        public void onClick (DialogInterface dialog, int whichButton) {
                            currentMenuDialog.show ();
                        }
                    });
                    abe.setNegativeButton ("Cancel", null);
                    abe.show ();
                }
            }
        });

        ab.setNegativeButton ("Cancel", null);

        currentMenuDialog = ab.show ();
    }

    private static String AbbreviateKey (String key)
    {
        if (key.length () > 50) {
            key = key.substring (0, 24) + " ... " + key.substring (key.length () - 24);
        }
        return key;
    }

    private void WriteKeypairFiles (KeyPair kpair, byte[] prvbin, byte[] pubbin)
    {
        String ident  = kpair.getPublicKeyComment ();
        String prvfnm = sshclient.getPrivatekeyfilename (ident);
        String pubfnm = sshclient.getPublickeyfilename (ident);
        try {
            sshclient.getMasterPassword ().WriteEncryptedFileBytes (prvfnm, prvbin);
            sshclient.getMasterPassword ().WriteEncryptedFileBytes (pubfnm, pubbin);
            sshclient.ErrorAlert ("Keypair name and fingerprint", ident + "\n" + kpair.getFingerPrint ());
        } catch (Exception e) {
            Log.e (TAG, "error writing keypair", e);
            //noinspection ResultOfMethodCallIgnored
            new File (prvfnm).delete ();
            //noinspection ResultOfMethodCallIgnored
            new File (pubfnm).delete ();
            AlertDialog.Builder abe = new AlertDialog.Builder (sshclient);
            abe.setTitle ("Keypair write error");
            abe.setMessage (SshClient.GetExMsg (e));
            abe.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                public void onClick (DialogInterface dialog, int whichButton) {
                    currentMenuDialog.show ();
                }
            });
            abe.setNegativeButton ("Cancel", null);
            abe.show ();
        }
    }

    /**
     * Send the public key to the host so it can add it to its authorized_keys file.
     * User should do something like 'cat > x.tmp' before doing this menu option
     * then concat that x.tmp onto the end of their ~/.ssh/authorized_keys file.
     *
     * Can also send private key for backup purposes.
     */
    private String SendEncryptedKeyFileToClipboard (String filename)
    {
        StringBuilder sb = new StringBuilder ();
        try {
            BufferedReader br = new BufferedReader (sshclient.getMasterPassword ().EncryptedFileReader (filename), 4096);
            int ch;
            while ((ch = br.read ()) >= 0) {
                sb.append ((char)ch);
            }
            br.close ();
        } catch (Exception e) {
            Log.w (TAG, "error reading " + filename, e);
            sshclient.ErrorAlert ("Error reading " + filename, SshClient.GetExMsg (e));
            return null;
        }
        return sb.toString ();
    }
}
