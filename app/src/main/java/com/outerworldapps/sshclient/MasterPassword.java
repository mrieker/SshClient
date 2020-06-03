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


import android.annotation.SuppressLint;
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
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.LinkedList;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MasterPassword {
    public static final String TAG = "SshClient";
    public static final long AUTH_LIFETIME = 24*60*60*1000;  // 24hrs

    private static final String THEGOODPW = "qwerasdv82u34l;jhsd90v672154jhgwef8a3o2i45yq2w98fyaile54y1298346aslidyq239846sdlih3l457q29836" +
            "293487ck3u8t3465e23gs6r77ws7rwhkfls46h34982qgqish1642ixhg1lwzh`1-2s1-37[efuw;sdcjpw91027-107ao867`096iauz895e1iu2t`-=`=s;lcn4";

    private AlertDialog currentMenuDialog;  // currently displayed AlertDialog (if needed by its callbacks)
    private File masterpwfile;              // stores validation parameters for master password
    private long lastAuthentication;        // elapsedRealtime() of last authentication
    private SecureRandom secrand;
    private SshClient sshclient;
    private WrappedStreams readWrapper;
    private WrappedStreams writeWrapper;

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
        if ((readWrapper != null) && (writeWrapper != null) && (lastAuthentication + AUTH_LIFETIME > now)) {
            sshclient.HaveMasterPassword ();
            return;
        }
        readWrapper = writeWrapper = null;
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
                writeWrapper = readWrapper = new WrappedECBStream (THEGOODPW);
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
            // try to read using new CBC format
            readWrapper = new WrappedCBCStream (masterpasswordstring);
            if (!VerifyMasterPassword ()) {
                // try to read using old EBC format
                readWrapper = new WrappedECBStream (masterpasswordstring);
                if (!VerifyMasterPassword ()) {
                    throw new Exception ("bad password");
                }
                // convert old ECB format to CBC format
                SetChangeMasterPasswordWork (masterpasswordstring);
            }
            writeWrapper = readWrapper;
            // tell client master password is good
            sshclient.HaveMasterPassword ();
        } catch (Exception e) {
            readWrapper = writeWrapper = null;
            Log.w (TAG, "error verifying " + masterpwfile.getPath (), e);
            PromptForMasterPassword ();
        }
    }

    private boolean VerifyMasterPassword ()
    {
        try {
            BufferedReader br = new BufferedReader (EncryptedFileReader (masterpwfile.getPath ()), 1024);
            try {
                @SuppressWarnings("unused")
                String salt = br.readLine ();  // random salt string
                String good = br.readLine ();  // a known text
                return good.equals (THEGOODPW);
            } finally {
                br.close ();
            }
        } catch (Exception e) {
            return false;
        }
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
        try {
            SetChangeMasterPasswordWork (masterpasswordstring);
            sshclient.ErrorAlert ("Set/Change master password", "New master password set");
        } catch (Exception e) {
            Log.e (TAG, "error setting/changing master password", e);
            sshclient.ErrorAlert ("Error setting/changing master password", SshClient.GetExMsg (e));
        }
    }

    /**
     * Re-encrypt files with new master password.
     * Also use newest algorithm.
     */
    private void SetChangeMasterPasswordWork (String masterpasswordstring)
            throws Exception
    {
        // set up to write using new algorithm and password
        writeWrapper = new WrappedCBCStream (masterpasswordstring);

        // read all .enc files using old algorithm and password
        // write them to .enc.tmp files using new algorithm and password
        File[] oldfiles = sshclient.getFilesDir ().listFiles ();
        for (File oldfile : oldfiles) {
            String oldpath = oldfile.getPath ();
            if (oldpath.endsWith (".enc")) {
                byte[] plainbin = ReadEncryptedFileBytes (oldpath);
                WriteEncryptedFileBytes (oldpath + ".tmp", plainbin);
            }
        }

        // write out new temp master password file
        File masterpwtemp = new File (masterpwfile.getPath () + ".tmp");
        BufferedWriter bw = new BufferedWriter (EncryptedFileWriter (masterpwtemp.getPath ()));
        bw.write (GenerateRandomSalt ());
        bw.newLine ();
        bw.write (THEGOODPW);
        bw.newLine ();
        bw.close ();

        // rename master password and data temp files to permanent names
        // a crash in here and it's borqed
        for (File oldfile : oldfiles) {
            String oldpath = oldfile.getPath ();
            if (oldpath.endsWith (".enc") && !oldpath.endsWith ("master_password.enc")) {
                RenameTempToPerm (oldpath);
            }
        }
        RenameTempToPerm (masterpwfile.getPath ());

        // use new algorithm and password to read from now on
        readWrapper = writeWrapper;
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

    // path must be in top-level files directory and end with .enc so SetChangeMasterPassword() will re-encrypt it
    public Reader EncryptedFileReader (String path)
            throws InvalidAlgorithmParameterException, InvalidKeyException, IOException, NoSuchAlgorithmException, NoSuchPaddingException
    {
        InputStream ciphertextstream = readWrapper.WrapInputStream (new FileInputStream (path));
        return new InputStreamReader (ciphertextstream);
    }

    // path must be in top-level files directory and end with .enc so SetChangeMasterPassword() will re-encrypt it
    public byte[] ReadEncryptedFileBytes (String path)
            throws IOException, InvalidAlgorithmParameterException, InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException
    {
        InputStream ciphertextstream = readWrapper.WrapInputStream (new FileInputStream (path));
        try {
            LinkedList<byte[]> list = new LinkedList<> ();
            int total = 0;
            while (true) {
                byte[] bytes = new byte[4000];
                int rc = ciphertextstream.read (bytes, 2, bytes.length - 2);
                if (rc <= 0) break;
                bytes[0] = (byte) rc;
                bytes[1] = (byte) (rc >> 8);
                list.add (bytes);
                total += rc;
            }
            byte[] output = new byte[total];
            total = 0;
            for (byte[] bytes : list) {
                int rc = (bytes[0] & 0xFF) | (bytes[1] << 8);
                System.arraycopy (bytes, 2, output, total, rc);
                total += rc;
            }
            return output;
        } finally {
            ciphertextstream.close ();
        }
    }

    // path must be in top-level files directory and end with .enc so SetChangeMasterPassword() will re-encrypt it
    public BufferedWriter EncryptedFileWriter (String path)
            throws InvalidAlgorithmParameterException, InvalidKeyException, IOException, NoSuchAlgorithmException, NoSuchPaddingException
    {
        return new BufferedWriter (new OutputStreamWriter (EncryptedFileOutputStream (path)));
    }

    // path must be in top-level files directory and end with .enc so SetChangeMasterPassword() will re-encrypt it
    public OutputStream EncryptedFileOutputStream (String path)
            throws InvalidAlgorithmParameterException, InvalidKeyException, IOException, NoSuchAlgorithmException, NoSuchPaddingException
    {
        return writeWrapper.WrapOutputStream (new FileOutputStream (path));
    }

    // path must be in top-level files directory and end with .enc so SetChangeMasterPassword() will re-encrypt it
    public void WriteEncryptedFileBytes (String path, byte[] data)
            throws IOException, InvalidAlgorithmParameterException, InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException
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

    /**
     * Wrap the encrypted files using whatever algorithm.
     */
    private static abstract class WrappedStreams {
        public abstract InputStream WrapInputStream (InputStream ciphertextstream)
                throws InvalidAlgorithmParameterException, InvalidKeyException, IOException,
                NoSuchAlgorithmException, NoSuchPaddingException;

        public abstract OutputStream WrapOutputStream (OutputStream ciphertextstream)
                throws InvalidAlgorithmParameterException, InvalidKeyException, IOException,
                NoSuchAlgorithmException, NoSuchPaddingException;
    }

    // newer more secure algorithm
    private class WrappedCBCStream extends WrappedStreams {
        private SecretKeySpec secretkeyspec;    // used to encrypt/decrypt our data files

        public WrappedCBCStream (String masterpasswordstring)
                throws NoSuchAlgorithmException, UnsupportedEncodingException
        {
            MessageDigest md = MessageDigest.getInstance ("SHA-256");
            secretkeyspec = new SecretKeySpec (md.digest (masterpasswordstring.getBytes ("UTF-8")), "AES");
        }

        @Override
        public InputStream WrapInputStream (InputStream ciphertextstream)
                throws InvalidAlgorithmParameterException, InvalidKeyException, IOException, NoSuchAlgorithmException, NoSuchPaddingException
        {
            Cipher cipher = Cipher.getInstance ("AES/CBC/PKCS5Padding");
            IvParameterSpec iv = new IvParameterSpec (ReadIvBytes (ciphertextstream));
            cipher.init (Cipher.DECRYPT_MODE, secretkeyspec, iv);
            return new CipherInputStream (ciphertextstream, cipher);
        }

        @Override
        public OutputStream WrapOutputStream (OutputStream ciphertextstream)
                throws InvalidAlgorithmParameterException, InvalidKeyException, IOException, NoSuchAlgorithmException, NoSuchPaddingException
        {
            Cipher cipher = Cipher.getInstance ("AES/CBC/PKCS5Padding");
            IvParameterSpec iv = new IvParameterSpec (WriteIvBytes (ciphertextstream));
            cipher.init (Cipher.ENCRYPT_MODE, secretkeyspec, iv);
            return new CipherOutputStream (ciphertextstream, cipher);
        }

        private byte[] ReadIvBytes (InputStream ciphertextstream)
                throws IOException
        {
            byte[] randbytes = new byte[16];
            for (int i = 0; i < 16;) {
                int rc = ciphertextstream.read (randbytes, i, 16 - i);
                if (rc <= 0) throw new IOException ("eof reading iv bytes");
                i += rc;
            }
            return randbytes;
        }

        private byte[] WriteIvBytes (OutputStream ciphertextstream)
                throws IOException
        {
            if (secrand == null) secrand = new SecureRandom ();
            byte[] randbytes = new byte[16];
            secrand.nextBytes (randbytes);
            ciphertextstream.write (randbytes);
            return randbytes;
        }
    }

    // old default android insecure algorithm
    private static class WrappedECBStream extends WrappedStreams {
        private SecretKeySpec secretkeyspec;    // used to encrypt/decrypt our data files

        public WrappedECBStream (String masterpasswordstring)
                throws NoSuchAlgorithmException, UnsupportedEncodingException
        {
            MessageDigest md = MessageDigest.getInstance ("SHA-256");
            secretkeyspec = new SecretKeySpec (md.digest (masterpasswordstring.getBytes ("UTF-8")), "AES");
        }

        @Override
        public InputStream WrapInputStream (InputStream ciphertextstream)
                throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException
        {
            @SuppressLint("GetInstance")
            Cipher cipher = Cipher.getInstance ("AES/ECB/PKCS5Padding");
            cipher.init (Cipher.DECRYPT_MODE, secretkeyspec);
            return new CipherInputStream (ciphertextstream, cipher);
        }

        @Override
        public OutputStream WrapOutputStream (OutputStream ciphertextstream)
                throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException
        {
            @SuppressLint("GetInstance")
            Cipher cipher = Cipher.getInstance ("AES/ECB/PKCS5Padding");
            cipher.init (Cipher.ENCRYPT_MODE, secretkeyspec);
            return new CipherOutputStream (ciphertextstream, cipher);
        }
    }
}
