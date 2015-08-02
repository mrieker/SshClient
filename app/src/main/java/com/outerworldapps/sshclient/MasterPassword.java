/**
 * Functions related to the master password.
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
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

public class MasterPassword {
    public static final String TAG = "SshClient";
    public static final long AUTH_LIFETIME = 24*60*60*1000;  // 24hrs

    private static final String THEGOODPW = "qwerasdv82u34l;jhsd90v672154jhgwef8a3o2i45yq2w98fyaile54y1298346aslidyq239846sdlih3l457q29836" +
            "293487ck3u8t3465e23gs6r77ws7rwhkfls46h34982qgqish1642ixhg1lwzh`1-2s1-37[efuw;sdcjpw91027-107ao867`096iauz895e1iu2t`-=`=s;lcn4";

    private AlertDialog currentMenuDialog;  // currently displayed AlertDialog (if needed by its callbacks)
    private File masterpwfile;              // stores validation parameters for master password
    private long lastAuthentication;        // elapsedRealtime() of last authentication
    private SecretKeySpec secretkeyspec;    // used to encrypt/decrypt our data files
    private SecureRandom secrand;
    private SshClient sshclient;

    public MasterPassword (SshClient sc)
    {
        sshclient = sc;
    }

    /***********************\
     *  Startup functions  *
    \***********************/

    public void Authenticate ()
    {
        /*
         * If we have already authenticated, make sure it wasn't too long ago.
         * This may be called from onResume() in which case we may already be
         * authenticated.
         */
        long now = SystemClock.elapsedRealtime ();
        if ((secretkeyspec != null) && (lastAuthentication + AUTH_LIFETIME > now)) {
            sshclient.HaveMasterPassword ();
            return;
        }
        secretkeyspec = null;
        lastAuthentication = now;

        /*
         * If master password file exists, prompt user for the master password before we do anything.
         * Note that if someone maliciously deleted the master password file to skip password prompt,
         * we won't be able to decrypt our data files.
         */
        masterpwfile = new File (sshclient.getFilesDir (), "master_password.enc");
        if (masterpwfile.exists ()) {
            PromptForMasterPassword ();
        } else {

            /*
             * No master password file exists, use our fixed encryption key
             * until user sets a master password, if ever.
             */
            try {
                MessageDigest md = MessageDigest.getInstance ("SHA-256");
                byte[] mdbytes = md.digest (THEGOODPW.getBytes ("UTF-8"));
                secretkeyspec = new SecretKeySpec (mdbytes, "AES");
            } catch (Exception e) {
                Log.e (TAG, "error setting up null encryption", e);
                sshclient.ErrorAlert ("Error setting up null encryption", SshClient.GetExMsg (e));
                return;
            }
            sshclient.HaveMasterPassword ();
        }
    }

    /**
     * A master password file exists so prompt the user to enter it.
     */
    private void PromptForMasterPassword ()
    {
        final EditText masterPasswordText = sshclient.MyEditText ();
        masterPasswordText.setInputType (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        masterPasswordText.setOnEditorActionListener (new TextView.OnEditorActionListener () {
            @Override // TextView.OnEditorActionListener
            public boolean onEditorAction (TextView v, int actionId, KeyEvent ke)
            {
                if ((actionId == EditorInfo.IME_ACTION_DONE) || (actionId == EditorInfo.IME_ACTION_NEXT)) {
                    currentMenuDialog.dismiss ();
                    EnteredMasterPassword (masterPasswordText);
                    return true;
                }
                return false;
            }
        });
        AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Enter master password");
        ab.setView (masterPasswordText);
        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
            @Override
            public void onClick (DialogInterface dialog, int whichButton)
            {
                EnteredMasterPassword (masterPasswordText);
            }
        });
        ab.setNegativeButton ("Cancel", new DialogInterface.OnClickListener () {
            @Override
            public void onClick (DialogInterface dialog, int whichButton)
            {
                sshclient.finish ();
            }
        });
        ab.setOnCancelListener (new DialogInterface.OnCancelListener () {
            @Override
            public void onCancel (DialogInterface dialogInterface)
            {
                sshclient.finish ();
            }
        });
        currentMenuDialog = ab.show ();
    }

    /**
     * User has presumably entered the master password, scrape it from text box and hashit.
     */
    private void EnteredMasterPassword (TextView masterPasswordText)
    {
        try {
            String masterpasswordstring = masterPasswordText.getText ().toString ().trim ();
            MessageDigest md = MessageDigest.getInstance ("SHA-256");
            byte[] masterpassworddigest = md.digest (masterpasswordstring.getBytes ("UTF-8"));
            secretkeyspec = new SecretKeySpec (masterpassworddigest, "AES");
            BufferedReader br = new BufferedReader (EncryptedFileReader (masterpwfile.getPath ()), 1024);
            try {
                String salt = br.readLine ();  // random salt string
                String good = br.readLine ();  // a known text
                // usually can't even read the lines if pw is bad but give it a final check...
                if (!good.equals (THEGOODPW)) {
                    throw new Exception ("data check bad");
                }
            } finally {
                br.close ();
            }
        } catch (Exception e) {
            Log.w (TAG, "error verifying " + masterpwfile.getPath (), e);
            PromptForMasterPassword ();
            return;
        }
        sshclient.HaveMasterPassword ();
    }

    /********************\
     *  Menu functions  *
    \********************/

    /**
     * Show the master password menu.
     */
    public void ShowMenu ()
    {
        final AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Master Password");
        ab.setMessage ("encrypts internal database");

        LinearLayout llv = new LinearLayout (sshclient);
        llv.setOrientation (LinearLayout.VERTICAL);

        final EditText pw1txt = sshclient.MyEditText ();
        final EditText pw2txt = sshclient.MyEditText ();
        pw1txt.setSingleLine (true);
        pw2txt.setSingleLine (true);
        pw1txt.setHint ("enter new master password");
        pw2txt.setHint ("enter same password again");
        pw1txt.setInputType (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw2txt.setInputType (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        llv.addView (pw1txt);
        llv.addView (pw2txt);

        ScrollView sv = new ScrollView (sshclient);
        sv.addView (llv);

        ab.setView (sv);

        ab.setPositiveButton ("Set/Change", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton) {
                String pw1str = pw1txt.getText ().toString ().trim ();
                String pw2str = pw2txt.getText ().toString ().trim ();
                if (pw1str.equals ("") || !pw1str.equals (pw2str)) {
                    sshclient.ErrorAlert ("Set/Change master password", "master passwords are empty or do not match");
                    currentMenuDialog.show ();
                } else {
                    SetChangeMasterPassword (pw2str);
                }
            }
        });

        ab.setNegativeButton ("Cancel", null);

        currentMenuDialog = ab.show ();
    }

    /**
     * Set or Change the master password to the given string.
     */
    private void SetChangeMasterPassword (String masterpasswordstring)
    {
        String savedloginsfilename = sshclient.getSavedlogins ().GetFileName ();
        Set<String> keypairidents = new LocalKeyPairMenu (sshclient).GetExistingKeypairIdents ().keySet ();

        try {
            SecretKeySpec oldsecretkeyspec = secretkeyspec;
            MessageDigest md = MessageDigest.getInstance ("SHA-256");
            SecretKeySpec newsecretkeyspec = new SecretKeySpec (md.digest (masterpasswordstring.getBytes ("UTF-8")), "AES");

            // re-encrypt all data files to temp files
            String vncPortNumberPath = new File (sshclient.getFilesDir (), "vncportnumbers.enc").getPath ();
            String vncPasswordPath   = new File (sshclient.getFilesDir (), "vncpasswords.enc").getPath ();
            boolean havesavedlogins  = ReEncryptFile (oldsecretkeyspec, newsecretkeyspec, savedloginsfilename);
            boolean haveknownhosts   = ReEncryptFile (oldsecretkeyspec, newsecretkeyspec, sshclient.getKnownhostsfilename ());
            boolean havetunnels      = ReEncryptFile (oldsecretkeyspec, newsecretkeyspec, sshclient.getTunnelMenu ().getTunnelFileName ());
            boolean havevncports     = ReEncryptFile (oldsecretkeyspec, newsecretkeyspec, vncPortNumberPath);
            boolean havevncpwds      = ReEncryptFile (oldsecretkeyspec, newsecretkeyspec, vncPasswordPath);
            for (String ident : keypairidents) {
                ReEncryptFile (oldsecretkeyspec, newsecretkeyspec, sshclient.getPrivatekeyfilename (ident));
                ReEncryptFile (oldsecretkeyspec, newsecretkeyspec, sshclient.getPublickeyfilename  (ident));
            }

            // write out new temp master password file
            // also activates new master key
            secretkeyspec = newsecretkeyspec;
            File masterpwtemp = new File (masterpwfile.getPath () + ".tmp");
            PrintWriter pw = new PrintWriter (EncryptedFileWriter (masterpwtemp.getPath ()));
            pw.println (GenerateRandomSalt ());
            pw.println (THEGOODPW);
            pw.close ();

            // rename master password and data temp files to permanent names
            // a crash in here and it's borked
            if (havesavedlogins) RenameTempToPerm (savedloginsfilename);
            if (haveknownhosts)  RenameTempToPerm (sshclient.getKnownhostsfilename ());
            if (havetunnels)     RenameTempToPerm (sshclient.getTunnelMenu ().getTunnelFileName ());
            if (havevncports)    RenameTempToPerm (vncPortNumberPath);
            if (havevncpwds)     RenameTempToPerm (vncPasswordPath);
            for (String ident : keypairidents) {
                RenameTempToPerm (sshclient.getPrivatekeyfilename (ident));
                RenameTempToPerm (sshclient.getPublickeyfilename  (ident));
            }
            RenameTempToPerm (masterpwfile.getPath ());

            sshclient.ErrorAlert ("Set/Change master password", "New master password set");
        } catch (Exception e) {
            Log.e (TAG, "error setting/changing master password", e);
            sshclient.ErrorAlert ("Error setting/changing master password", SshClient.GetExMsg (e));
        }
    }

    private boolean ReEncryptFile (SecretKeySpec oldsecretkeyspec, SecretKeySpec newsecretkeyspec, String permfilename)
            throws IOException, InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException
    {
        secretkeyspec = oldsecretkeyspec;
        byte[] plainbin = ReadEncryptedFileBytesOrNull (permfilename);
        if (plainbin == null) return false;

        String tempfilename = permfilename + ".tmp";
        secretkeyspec = newsecretkeyspec;
        WriteEncryptedFileBytes (tempfilename, plainbin);
        return true;
    }

    private String GenerateRandomSalt ()
    {
        if (secrand == null) secrand = new SecureRandom ();
        return new BigInteger (256, secrand).toString (32);
    }

    /**************************************\
     *  Encrypted file reading & writing  *
     *  Uses master password key          *
     \**************************************/

    public Reader EncryptedFileReader (String path)
            throws FileNotFoundException, InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException
    {
        InputStream ciphertextstream = new FileInputStream (path);
        if (secretkeyspec != null) {
            Cipher cipher = Cipher.getInstance ("AES");
            cipher.init (Cipher.DECRYPT_MODE, secretkeyspec);
            ciphertextstream = new CipherInputStream (ciphertextstream, cipher);
        }
        return new InputStreamReader (ciphertextstream);
    }

    public byte[] ReadEncryptedFileBytes (String path)
            throws IOException, InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException
    {
        InputStream ciphertextstream = new FileInputStream (path);
        try {
            if (secretkeyspec != null) {
                Cipher cipher = Cipher.getInstance ("AES");
                cipher.init (Cipher.DECRYPT_MODE, secretkeyspec);
                ciphertextstream = new CipherInputStream (ciphertextstream, cipher);
            }
            byte[] output = new byte[256];
            int offset = 0;
            while (true) {
                if (offset >= output.length) {
                    byte[] newout = new byte[output.length+256];
                    System.arraycopy (output, 0, newout, 0, offset);
                    output = newout;
                }
                int rc = ciphertextstream.read (output, offset, output.length - offset);
                if (rc <= 0) break;
                offset += rc;
            }
            if (output.length > offset) {
                byte[] newout = new byte[offset];
                System.arraycopy (output, 0, newout, 0, offset);
                output = newout;
            }
            return output;
        } finally {
            ciphertextstream.close ();
        }
    }

    public Writer EncryptedFileWriter (String path)
            throws FileNotFoundException, InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException
    {
        return new OutputStreamWriter (EncryptedFileOutputStream (path));
    }

    public OutputStream EncryptedFileOutputStream (String path)
            throws FileNotFoundException, InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException
    {
        OutputStream ciphertextstream = new FileOutputStream (path);
        if (secretkeyspec != null) {
            Cipher cipher = Cipher.getInstance ("AES");
            cipher.init (Cipher.ENCRYPT_MODE, secretkeyspec);
            ciphertextstream = new CipherOutputStream (ciphertextstream, cipher);
        }
        return ciphertextstream;
    }


    private byte[] ReadEncryptedFileBytesOrNull (String path)
            throws IOException, InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException
    {
        if (!new File (path).exists ()) return null;
        return ReadEncryptedFileBytes (path);
    }

    public void WriteEncryptedFileBytes (String path, byte[] data)
            throws IOException, InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException
    {
        OutputStream ciphertext = EncryptedFileOutputStream (path);
        ciphertext.write (data);
        ciphertext.close ();
    }

    public static void RenameTempToPerm (String permname)
            throws IOException
    {
        if (!new File (permname + ".tmp").renameTo (new File (permname))) {
            throw new IOException ("error renaming " + permname + ".tmp to " + permname);
        }
    }
}
