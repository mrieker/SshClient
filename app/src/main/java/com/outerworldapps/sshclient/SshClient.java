/**
 * Basic SSH Client app.
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
import java.util.LinkedList;
import java.util.Set;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.text.ClipboardManager;
import android.text.InputType;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;

public class SshClient extends Activity {
    public static final String TAG = "SshClient";

    public static final float UNIFORM_TEXT_SIZE = 20.0F;

    private static final String THEGOODPW = "qwerasdv82u34l;jhsd90v672154jhgwef8a3o2i45yq2w98fyaile54y1298346aslidyq239846sdlih3l457q29836" +
            "293487ck3u8t3465e23gs6r77ws7rwhkfls46h34982qgqish1642ixhg1lwzh`1-2s1-37[efuw;sdcjpw91027-107ao867`096iauz895e1iu2t`-=`=s;lcn4";

    private AlertDialog currentMenuDialog;        // currently displayed AlertDialog (if needed by its callbacks)
    private boolean hasAgreed;
    private File masterpwfile;                    // stores validation parameters for master password
    private int lastSessionNumber;
    private LinkedList<MySession> allsessions = new LinkedList<MySession> ();
    private MyHostKeyRepo myhostkeyrepo;          // holds list of known hosts
    private MySession currentsession;             // session currently selected by user
    private SavedLogins savedlogins;              // list of username@hostname[:portnumber] we have connected to
    private SecretKeySpec secretkeyspec;          // used to encrypt/decrypt our data files
    private SecureRandom secrand;
    private Settings settings;                    // user settable settings
    private String knownhostsfilename;            // name of file that holds known remote hosts (hosts we have the signature for)
    public  String internalClipboard = "";
    private String privatekeywildname;            // name of file that holds the local private key
    private String publickeywildname;             // name of file that holds the local public key

    public LinkedList<MySession> getAllsessions () { return allsessions; }
    public MyHostKeyRepo getMyhostkeyrepo () { return myhostkeyrepo; }
    public MySession getCurrentsession () { return currentsession; }
    public int getNextSessionNumber () { return ++ lastSessionNumber; }
    public SavedLogins getSavedlogins () { return savedlogins; }
    public Settings getSettings () { return settings; }
    public String getKnownhostsfilename () { return knownhostsfilename; }
    public String getPrivatekeyfilename (String ident) { return privatekeywildname.replace ("*", ident); }
    public String getPublickeyfilename  (String ident) { return publickeywildname.replace  ("*", ident); }

    public void setCurrentsession (MySession s) { currentsession = s; }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate (Bundle savedInstanceState)
    {
        super.onCreate (savedInstanceState);

        Log.d (TAG, "starting");

        /*
         * Show user the help page which has licenses at the bottom.
         * Then wait for them to agree or exit if they don't agree.
         * The menu is disabled until they agree.
         */
        final SharedPreferences prefs = getPreferences (Activity.MODE_PRIVATE);
        if (prefs.getBoolean ("hasAgreed", false)) {
            HasAgreed ();
        } else {
            LinearLayout llv = new LinearLayout (this);
            llv.setOrientation (LinearLayout.VERTICAL);
            llv.addView (MakeHtmlView (R.raw.help, "help"));

            TextView tv = MyTextView ();
            tv.setText ("Note:  The above can be re-displayed later by clicking on the Android menu button then More then HELP.");
            llv.addView (tv);

            Button butok = MyButton ();
            butok.setText ("I ACCEPT the terms of the above licenses.");
            butok.setOnClickListener (new View.OnClickListener () {
                @Override
                public void onClick (View view)
                {
                    SharedPreferences.Editor editr = prefs.edit ();
                    editr.putBoolean ("hasAgreed", true);
                    editr.commit ();
                    HasAgreed ();
                }
            });
            llv.addView (butok);

            Button butcan = MyButton ();
            butcan.setText ("I DO NOT ACCEPT the terms of the above licenses.");
            butcan.setOnClickListener (new View.OnClickListener () {
                @Override
                public void onClick (View view)
                {
                    finish ();
                }
            });
            llv.addView (butcan);

            ScrollView sv = new ScrollView (this);
            sv.addView (llv);

            setContentView (sv);
        }
    }

    private void HasAgreed ()
    {
        hasAgreed = true;

        /*
         * If master password file exists, prompt user for the master password before we do anything.
         * Note that if someone maliciously deleted the master password file to skip password prompt,
         * we won't be able to decrypt our data files.
         */
        masterpwfile = new File (getFilesDir (), "master_password.enc");
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
                ErrorAlert ("Error setting up mull encryption", e.getMessage ());
                return;
            }
            HaveMasterPassword ();
        }
    }

    /**
     * A master password file exists so prompt the user to enter it.
     */
    private void PromptForMasterPassword ()
    {
        final EditText masterPasswordText = new EditText (this);
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
        AlertDialog.Builder ab = new AlertDialog.Builder (this);
        ab.setTitle ("Enter master password");
        ab.setView (masterPasswordText);
        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton)
            {
                EnteredMasterPassword (masterPasswordText);
            }
        });
        ab.setNegativeButton ("Cancel", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton)
            {
                finish ();
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
        HaveMasterPassword ();
    }

    /**
     * Master password, if any, is valid and we are ready to go.
     */
    private void HaveMasterPassword ()
    {
        /*
         * Now that we know how to read our datafiles, set everything else up...
         */
        knownhostsfilename = new File (getFilesDir (), "known_hosts.enc").getPath ();
        privatekeywildname = new File (getFilesDir (), "private_key_*.enc").getPath ();
        publickeywildname  = new File (getFilesDir (), "public_key_*.enc").getPath  ();
        savedlogins        = new SavedLogins (this);
        settings           = new Settings (this);
        myhostkeyrepo      = new MyHostKeyRepo (this);

        /*
         * We always have a current session, even if not connected to anything.
         */
        currentsession = new MySession (this);
        allsessions.addLast (currentsession);
        currentsession.ShowScreen ();

        Log.d (TAG, "started");
    }

    /**
     * Display an error alert dialog box, or really just anything that just
     * needs a simple OK button and doesn't need to wait for a response.
     */
    public void ErrorAlert (String title, String message)
    {
        AlertDialog.Builder ab = new AlertDialog.Builder (this);
        ab.setTitle (title);
        ab.setMessage (message);
        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton)
            { }
        });
        ab.show ();
    }

    /**
     * Make beep sound.
     */
    public static void MakeBeepSound ()
    {
        try {
            // requires VIBRATE permission
            //Vibrator vib = (Vibrator)getSystemService (Activity.VIBRATOR_SERVICE);
            //vib.vibrate (200);

            ToneGenerator tg = new ToneGenerator (AudioManager.STREAM_NOTIFICATION, 100);
            tg.startTone (ToneGenerator.TONE_PROP_BEEP);
        } catch (Exception e) {
            // sometimes ToneGenerator throws RuntimeException: 'Init failed'
            Log.w (TAG, "make beep sound error", e);
        }
    }

    /*************************\
     *  MENU key processing  *
    \*************************/

    // Display the main menu when the hardware menu button is clicked.
    @Override
    public boolean onCreateOptionsMenu (Menu menu)
    {
        // main menu
        menu.add ("Ctrl-...");
        menu.add ("Ctrl-C");
        menu.add ("Freeze");
        menu.add ("Paste");
        menu.add ("Tab");

        // extended menu
        menu.add ("clear screen");
        menu.add ("EXIT");
        menu.add ("HELP");
        menu.add ("known hosts");
        menu.add ("local keypairs");
        menu.add ("main screen");
        menu.add ("master password");
        menu.add ("saved logins");
        menu.add ("sessions");
        menu.add ("settings");

        return true;
    }

    // This is called when someone clicks on an item in the
    // main menu displayed by the hardware menu button.
    @Override
    public boolean onOptionsItemSelected (MenuItem menuItem)
    {
        if (!hasAgreed) return true;

        CharSequence sel = menuItem.getTitle ();

        if ("Ctrl-...".equals (sel)) currentsession.AssertCtrlKeyFlag ();
        if ("Ctrl-C".equals (sel)) currentsession.SendCharToHost (3);
        if ("Freeze".equals (sel)) currentsession.getScreentextview ().FreezeThaw ();
        if ("Paste".equals (sel)) PasteClipboardToHost ();
        if ("Tab".equals (sel)) currentsession.SendCharToHost (9);

        if ("clear screen".equals (sel)) currentsession.getScreentextview ().Clear ();
        if ("EXIT".equals (sel)) ExitButtonClicked ();
        if ("HELP".equals (sel)) ShowHelpScreen ();
        if ("known hosts".equals (sel)) myhostkeyrepo.ShowKnownHostMenu ();
        if ("local keypairs".equals (sel)) new LocalKeyPairMenu (this).ShowLocalKeyPairMenu ();
        if ("main screen".equals (sel)) currentsession.ShowScreen ();
        if ("master password".equals (sel)) ShowMasterPasswordMenu ();
        if ("saved logins".equals (sel)) savedlogins.ShowSavedLoginsMenu ();
        if ("sessions".equals (sel)) ShowSessionsMenu ();
        if ("settings".equals (sel)) settings.ShowMenu ();

        return true;
    }

    private void PasteClipboardToHost ()
    {
        if (currentsession.getScreentextview ().isFrozen ()) {
            ErrorAlert ("Paste to host", "disabled while screen frozen");
            return;
        }
        final AlertDialog.Builder ab = new AlertDialog.Builder (this);
        ab.setTitle ("Paste clipboard to host");
        ab.setPositiveButton ("Internal", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton)
            {
                currentsession.SendStringToHost (internalClipboard);
            }
        });
        ab.setNeutralButton ("External", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton)
            {
                ClipboardManager cbm = (ClipboardManager) getSystemService (CLIPBOARD_SERVICE);
                currentsession.SendStringToHost (cbm.getText ().toString ());
            }
        });
        ab.setNegativeButton ("Cancel", NullCancelListener);
        ab.show ();
    }

    private void ExitButtonClicked ()
    {
        final AlertDialog.Builder ab = new AlertDialog.Builder (this);
        ab.setTitle ("EXIT - Closes everything");
        ab.setMessage ("Are you sure?");
        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton)
            {
                System.exit (0);
            }
        });
        ab.setNegativeButton ("Cancel", NullCancelListener);
        ab.show ();
    }

    /**
     * Show the master password menu.
     */
    private void ShowMasterPasswordMenu ()
    {
        final AlertDialog.Builder ab = new AlertDialog.Builder (this);
        ab.setTitle ("Master Password");
        ab.setMessage ("encrypts internal database");

        LinearLayout llv = new LinearLayout (this);
        llv.setOrientation (LinearLayout.VERTICAL);

        final EditText pw1txt = MyEditText ();
        final EditText pw2txt = MyEditText ();
        pw1txt.setSingleLine (true);
        pw2txt.setSingleLine (true);
        pw1txt.setHint ("enter new master password");
        pw2txt.setHint ("enter same password again");
        pw1txt.setInputType (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw2txt.setInputType (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        llv.addView (pw1txt);
        llv.addView (pw2txt);

        ScrollView sv = new ScrollView (this);
        sv.addView (llv);

        ab.setView (sv);

        ab.setPositiveButton ("Set/Change", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton) {
                String pw1str = pw1txt.getText ().toString ().trim ();
                String pw2str = pw2txt.getText ().toString ().trim ();
                if (pw1str.equals ("") || !pw1str.equals (pw2str)) {
                    ErrorAlert ("Set/Change master password", "master passwords are empty or do not match");
                    currentMenuDialog.show ();
                } else {
                    SetChangeMasterPassword (pw2str);
                }
            }
        });

        ab.setNegativeButton ("Cancel", NullCancelListener);

        currentMenuDialog = ab.show ();
    }

    /**
     * Set or Change the master password to the given string.
     */
    private void SetChangeMasterPassword (String masterpasswordstring)
    {
        String savedloginsfilename = savedlogins.GetFileName ();
        Set<String> keypairidents = new LocalKeyPairMenu (this).GetExistingKeypairIdents ().keySet ();

        try {
            SecretKeySpec oldsecretkeyspec = secretkeyspec;
            MessageDigest md = MessageDigest.getInstance ("SHA-256");
            SecretKeySpec newsecretkeyspec = new SecretKeySpec (md.digest (masterpasswordstring.getBytes ("UTF-8")), "AES");

            // re-encrypt all data files to temp files
            boolean havesavedlogins = ReEncryptFile (oldsecretkeyspec, newsecretkeyspec, savedloginsfilename);
            boolean haveknownhosts  = ReEncryptFile (oldsecretkeyspec, newsecretkeyspec, knownhostsfilename);
            for (String ident : keypairidents) {
                ReEncryptFile (oldsecretkeyspec, newsecretkeyspec, privatekeywildname.replace ("*", ident));
                ReEncryptFile (oldsecretkeyspec, newsecretkeyspec, publickeywildname.replace  ("*", ident));
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
            if (haveknownhosts)  RenameTempToPerm (knownhostsfilename);
            for (String ident : keypairidents) {
                RenameTempToPerm (privatekeywildname.replace ("*", ident));
                RenameTempToPerm (publickeywildname.replace  ("*", ident));
            }
            RenameTempToPerm (masterpwfile.getPath ());

            ErrorAlert ("Set/Change master password", "New master password set");
        } catch (Exception e) {
            Log.e (TAG, "error setting/changing master password", e);
            ErrorAlert ("Error setting/changing master password", e.getMessage ());
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

    /**
     * Display list of current sessions and allow selection or create a new session.
     */
    private void ShowSessionsMenu ()
    {
        final AlertDialog.Builder ab = new AlertDialog.Builder (this);
        ab.setTitle ("Sessions");
        ab.setMessage ("- short click to make active\n- long click to disconnect");

        LinearLayout llv = new LinearLayout (this);
        llv.setOrientation (LinearLayout.VERTICAL);

        // listener so when button is clicked, dialog is dismissed and session is made current
        View.OnClickListener butlis = new View.OnClickListener () {
            @Override
            public void onClick (View view)
            {
                currentMenuDialog.dismiss ();

                MySession ms = (MySession) view.getTag ();
                ms.ShowScreen ();
            }
        };

        View.OnLongClickListener butlonglis = new View.OnLongClickListener () {
            @Override
            public boolean onLongClick (View view)
            {
                currentMenuDialog.dismiss ();

                final MySession ms = (MySession) view.getTag ();
                AlertDialog.Builder ab = new AlertDialog.Builder (SshClient.this);
                ab.setTitle ("Confirm disconnect session");
                ab.setMessage (ms.GetSessionName ());
                ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                    public void onClick (DialogInterface dialog, int whichButton) {
                        ms.Disconnect ();

                        // remove session from list of all sessions
                        MySession prev = null;
                        for (MySession s : allsessions) {
                            if (s == ms) break;
                            prev = s;
                        }
                        allsessions.remove (ms);

                        if (ms == currentsession) {

                            // make the previous session in the list current
                            if (prev == null) prev = allsessions.peek ();
                            if (prev == null) {
                                prev = new MySession (SshClient.this);
                                allsessions.addLast (prev);
                            }
                            prev.ShowScreen ();
                        }
                    }
                });
                ab.setNegativeButton ("Cancel", NullCancelListener);
                ab.show ();
                return true;
            }
        };

        // populate the list by scanning the allsessions list
        for (MySession ms : allsessions) {
            Button but = MyButton ();
            String name = ms.GetSessionName ();
            if (ms == currentsession) {
                but.setText (name);
                but.setTextColor (Color.BLUE);
            } else {
                but.setText (name);
            }
            but.setTag (ms);
            but.setOnClickListener (butlis);
            but.setOnLongClickListener (butlonglis);
            llv.addView (but);
        }

        // display menu with a New and a Cancel button
        ScrollView sv = new ScrollView (this);
        sv.addView (llv);

        ab.setView (sv);
        ab.setPositiveButton ("New", new DialogInterface.OnClickListener() {
            public void onClick (DialogInterface dialog, int whichButton)
            {
                MySession ms = new MySession (SshClient.this);
                allsessions.addLast (ms);
                ms.ShowScreen ();
            }
        });
        ab.setNegativeButton ("Cancel", NullCancelListener);

        currentMenuDialog = ab.show ();
    }

    /**
     * Display the help text from the help.html file.
     */
    private void ShowHelpScreen ()
    {
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_USER);
        setContentView (MakeHtmlView (R.raw.help, "help"));
    }

    private WebView MakeHtmlView (int resid, String name)
    {
        StringBuilder html = new StringBuilder ();
        char[] buf = new char[4096];
        int rc;
        try {
            InputStream is = getResources ().openRawResource (resid);
            InputStreamReader ir = new InputStreamReader (is);
            while ((rc = ir.read (buf)) > 0) {
                html.append (buf, 0, rc);
            }
            ir.close ();
        } catch (IOException ioe) {
            Log.e (TAG, "error reading " + name, ioe);
            ErrorAlert ("Error reading " + name, ioe.getMessage ());
            return null;
        }
        String htmlstr = html.toString ();

        try {
            PackageInfo pInfo = getPackageManager ().getPackageInfo (getPackageName (), 0);
            htmlstr = htmlstr.replace ("%versionname%", pInfo.versionName);
            htmlstr = htmlstr.replace ("%versioncode%", Integer.toString (pInfo.versionCode));
        } catch (PackageManager.NameNotFoundException nnfe)
        { }

        WebView viewer = new WebView (this);
        viewer.getSettings ().setBuiltInZoomControls (true);
        viewer.loadData (htmlstr, "text/html", null);
        return viewer;
    }


    /*********************************************************\
     *  Simple widget wrappers to provide uniform text size  *
    \*********************************************************/

    public Button MyButton ()
    {
        Button v = new Button (this);
        v.setTextSize (UNIFORM_TEXT_SIZE);
        return v;
    }

    public CheckBox MyCheckBox ()
    {
        CheckBox v = new CheckBox (this);
        v.setTextSize (UNIFORM_TEXT_SIZE);
        return v;
    }

    public EditText MyEditText ()
    {
        EditText v = new EditText (this);
        v.setTextSize (UNIFORM_TEXT_SIZE);
        return v;
    }

    public RadioButton MyRadioButton ()
    {
        RadioButton v = new RadioButton (this);
        v.setTextSize (UNIFORM_TEXT_SIZE);
        return v;
    }

    public TextView MyTextView ()
    {
        TextView v = new TextView (this);
        v.setTextSize (UNIFORM_TEXT_SIZE);
        return v;
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

    /***************\
     *  Utilities  *
    \***************/

    public static final DialogInterface.OnClickListener NullCancelListener = new DialogInterface.OnClickListener () {
        @Override
        public void onClick (DialogInterface dialogInterface, int i)
        { }
    };

    /**
     * Set Unix-like permissions on a file.
     */
    //public static int ChMod (String path, int mode)
    //        throws Exception
    //{
    //    Class fileUtils = Class.forName ("android.os.FileUtils");
    //    Method setPermissions = fileUtils.getMethod ("setPermissions", String.class, int.class, int.class, int.class);
    //    return (Integer) setPermissions.invoke (null, path, mode, -1, -1);
    //}

    public static void RenameTempToPerm (String permname)
            throws IOException
    {
        if (!new File (permname + ".tmp").renameTo (new File (permname))) {
            throw new IOException ("error renaming " + permname + ".tmp to " + permname);
        }
    }
}
