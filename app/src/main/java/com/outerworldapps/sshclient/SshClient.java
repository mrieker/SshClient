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


import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.support.annotation.NonNull;
import android.text.ClipboardManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;

@SuppressLint("SetTextI18n")
public class SshClient extends Activity {
    public static final String TAG = "SshClient";

    public static final float UNIFORM_TEXT_SIZE = 20.0F;

    // what to do when back button pressed
    public interface BackAction {
        boolean okToPop ();    // it is ok to pop this page
        void reshow ();        // how to reshow this page
        String name ();        // unique name for this page
        MySession session ();  // null if this page doesn't set session
    }

    private AlertDialog currentMenuDialog;        // currently displayed AlertDialog (if needed by its callbacks)
    private AlertDialog extendedMenuAD;
    private boolean masterPWGood;
    private FileExplorerView localFilesOnly;
    private HashMap<CharSequence,Runnable> allMainMenuItems = new HashMap<> ();
    private HelpView helpView;
    private int lastSessionNumber;
    public  InternalLogView internalLogView;
    private JSessionService jSessionService;
    private LinkedList<BackAction> backActionStack = new LinkedList<> ();
    private LinkedList<MySession> allsessions = new LinkedList<> ();
    private MasterPassword masterPassword;
    private Menu mainMenu;
    private MyHostKeyRepo myhostkeyrepo;          // holds list of known hosts
    private MySession currentsession;             // session currently selected by user
    private NetworkInterfacesView networkInterfacesView;
    public  KnownReadable knownReadables;
    private SavedLogins savedlogins;              // list of username@hostname[:portnumber] we have connected to
    private Settings settings;                    // user settable settings
    private String knownhostsfilename;            // name of file that holds known remote hosts (hosts we have the signature for)
    public  String internalClipboard = "";
    private String privatekeywildname;            // name of file that holds the local private key
    private String publickeywildname;             // name of file that holds the local public key
    private TunnelMenu tunnelMenu;                // shows list of tunnels for a given user@host:port
    private View contentView;                     // what was last set with setContentView()

    public LinkedList<MySession> getAllsessions () { return allsessions; }
    public MasterPassword getMasterPassword () { return masterPassword; }
    public MyHostKeyRepo getMyhostkeyrepo () { return myhostkeyrepo; }
    public int getNextSessionNumber () { return ++ lastSessionNumber; }
    public void setLastSessionNumber (int sn) { if (lastSessionNumber < sn) lastSessionNumber = sn; }
    public SavedLogins getSavedlogins () { return savedlogins; }
    public Settings getSettings () { return settings; }
    public String getKnownhostsfilename () { return knownhostsfilename; }
    public String getPrivatekeyfilename (String ident) { return privatekeywildname.replace ("*", ident); }
    public String getPublickeyfilename  (String ident) { return publickeywildname.replace  ("*", ident); }
    public TunnelMenu getTunnelMenu () { return tunnelMenu; }
    public JSessionService getJSessionService () { return jSessionService; }

    public void setCurrentsession (MySession s)
    {
        currentsession = s;
        jSessionService.currentSessionNumber = s.getSessionNumber ();
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate (Bundle savedInstanceState)
    {
        super.onCreate (savedInstanceState);

        // allow network IO from GUI thread
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        // allow startIntent() to let other apps read our files
        // https://stackoverflow.com/questions/38200282/android-os-fileuriexposedexception-file-storage-emulated-0-test-txt-exposed
        // also allows links in the HelpView to work so they don't get FileUriExposedException
        StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder ();
        StrictMode.setVmPolicy (builder.build ());

        /*
         * Bind to the JSessionService.
         */
        Log.d (TAG, "Binding JSessionService");
        JSessionService.bindit (this, new JSessionService.ConDiscon () {
            @Override
            public void onConDiscon (JSessionService jss)
            {
                jSessionService = jss;
                StartInitialSession ();
            }
        });
    }

    @Override
    public void onDestroy ()
    {
        super.onDestroy ();
        if (jSessionService != null) {
            Collection<ScreenDataThread> sdts = jSessionService.getAllScreenDataThreads ();
            Log.d (TAG, "onDestroy: " + sdts.size () + " thread(s)");
            for (ScreenDataThread sdt : sdts) {
                sdt.detach ();
            }
            Log.d (TAG, "Unbinding JSessionService");
            jSessionService.unbindit ();
        }
    }

    /**
     * Screen is being brought to front.
     */
    @Override
    public void onResume ()
    {
        super.onResume ();

        Log.d (TAG, "app starting");

        /*
         * Block back and menu keys until master password is validated.
         */
        masterPWGood = false;

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
            helpView = new HelpView (this);
            llv.addView (helpView);

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
                    editr.apply ();
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

    /**
     * User has, at some time in the past anyway, agreed to the license.
     */
    private void HasAgreed ()
    {
        // make sure we have a masterPassword struct set up
        if (masterPassword == null) {
            masterPassword = new MasterPassword (this);
        }

        // Please Stand By... so they can't interact with the screen
        TextView tv = MyTextView ();
        tv.setText ("checking master password...");
        super.setContentView (tv);

        // start (re-)authentication (might complete immediately)
        masterPassword.Authenticate ();
    }

    /**
     * Master password, if any, is valid and we are ready to go.
     */
    public void HaveMasterPassword ()
    {
        /*
         * Successfully (re-)authenticated, enable buttons and restore display.
         */
        masterPWGood = true;
        if (contentView != null) {
            super.setContentView (contentView);
        }

        /*
         * Now that we know how to read our datafiles, set everything else up...
         */
        if (knownhostsfilename == null) {
            knownhostsfilename    = new File (getFilesDir (), "known_hosts.enc").getPath ();
            privatekeywildname    = new File (getFilesDir (), "private_key_*.enc").getPath ();
            publickeywildname     = new File (getFilesDir (), "public_key_*.enc").getPath  ();
            savedlogins           = new SavedLogins (this);
            settings              = new Settings (this);
            myhostkeyrepo         = new MyHostKeyRepo (this);
            tunnelMenu            = new TunnelMenu (this);
            networkInterfacesView = new NetworkInterfacesView (this);
            internalLogView       = new InternalLogView (this);
        }

        Log.d (TAG, "app started");

        StartInitialSession ();
    }

    /**
     * If just being resumed presumably with all our state intact, we are done.
     * Otherwise, get session going, either an unconnected one or previous connections.
     */
    private void StartInitialSession ()
    {
        if ((knownhostsfilename != null) && (jSessionService != null) && (currentsession == null)) {

            /*
             * See if JSessionService has any saved threads.
             */
            Collection<ScreenDataThread> savedThreads = jSessionService.getAllScreenDataThreads ();
            MySession mysession = null;
            if (!savedThreads.isEmpty ()) {

                /*
                 * There are saved threads, each one is a connected session..
                 * Restore each thread to a session struct then select the current one.
                 */
                for (ScreenDataThread sdt : savedThreads) {
                    MySession ms = new MySession (this, sdt);
                    if (ms.getSessionNumber () == jSessionService.currentSessionNumber) {
                        mysession = ms;
                    }
                    allsessions.addLast (ms);
                }
            }

            /*
             * If we didn't find a session to restore, make up a blank one
             * so we always have a current session.
             */
            if (mysession == null) {
                mysession = new MySession (this);
                allsessions.addLast (mysession);
            }

            /*
             * Whatever session we selected as current, display it.
             */
            mysession.ShowScreen ();
        }
    }

    /**
     * Intercept these calls so we know what we are displaying.
     */
    @Override
    public void setContentView (View v)
    {
        contentView = v;
        super.setContentView (v);
        if (v instanceof HasMainMenu) {
            ((HasMainMenu) v).MakeMainMenu ();
        } else {
            SetDefaultMMenuItems ();
        }
    }

    /****************************\
     *  BACK Button Processing  *
    \****************************/

    /**
     * If BACK button pressed while in help screen,
     * use it to navigate backward through history.
     *
     * Otherwise, process it normally, ie, exit app.
     * JSessionService should keep connections alive
     * even if user exited by mistake.
     */
    @Override
    public void onBackPressed ()
    {
        // if back button pressed while asking for master password,
        // exit the app
        if (!masterPWGood) {
            finish ();
            return;
        }

        // get screen currently displayed
        BackAction curScreen = backActionStack.removeFirst ();

        // if it says it can't be popped now, push it back and that's it for now
        // it should alert the user as to what to do to get out
        if (!curScreen.okToPop ()) {
            backActionStack.addFirst (curScreen);
            return;
        }

        // if there is something internal to go back to, go back to it
        if (!popBackAction ()) {

            // nothing to go back to, exit the app
            // but do not disconnect sessions in case exiting by mistake
            finish ();
        }
    }

    /**
     * A new page was just made current, push an entry on the stack that can reproduce it
     * in case the back button tries to go back to it sometime later.
     */
    public void pushBackAction (BackAction ba)
    {
        // if same page is buried way back in the stack, remove the old entry
        String baname = ba.name ();
        for (Iterator<BackAction> it = backActionStack.iterator (); it.hasNext ();) {
            BackAction dup = it.next ();
            if (dup.name ().equals (baname)) {
                it.remove ();
            }
        }

        // make this page the currently displayed page
        backActionStack.addFirst (ba);
    }

    /**
     * Pop back to top element on back action stack.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean popBackAction ()
    {
        // find latest screen that sets a session as current
        currentsession = null;
        for (BackAction ba : backActionStack) {
            currentsession = ba.session ();
            if (currentsession != null) break;
        }

        // if no session in anything left on stack, tell caller all empty
        if (currentsession == null) {

            // get rid of left over junk like help
            // we can never go back to any of it
            // cuz there is no session behind it
            backActionStack.clear ();

            // tell caller that stack was empty
            return false;
        }

        // pop very latest off stack and it will re-push itself if it wants to
        backActionStack.removeFirst ().reshow ();

        // tell caller we had something to pop to
        return true;
    }

    /*************************\
     *  MENU key processing  *
    \*************************/

    // a view that gets displayed via setContentView() and has main menu items should implement this interface
    public interface HasMainMenu {
        void MakeMainMenu ();
    }

    // Display the main menu when the hardware menu button is clicked.
    @Override
    public boolean onCreateOptionsMenu (Menu menu)
    {
        // main menu
        mainMenu = menu;
        if ((contentView != null) && (contentView instanceof HasMainMenu)) {
            ((HasMainMenu) contentView).MakeMainMenu ();
        } else {
            SetDefaultMMenuItems ();
        }

        // extended menu
        // make an AlertDialog with scrolled buttons
        // cuz scrolling the Android-provided extended menu doesn't always work
        LinearLayout xmll = new LinearLayout (this);
        xmll.setOrientation (LinearLayout.VERTICAL);

        AddXMenuItem (xmll, "clear screen", new Runnable () {
            public void run () {
                currentsession.getScreentextview ().Clear ();
            }
        });
        AddXMenuItem (xmll, "clipboard <-> file", new Runnable () {
            public void run () {
                currentsession.ClipboardToFromFileMenu ();
            }
        });
        AddXMenuItem (xmll, "disconnect", new Runnable () {
            public void run () {
                DisconnectConfirm (currentsession);
            }
        });
        AddXMenuItem (xmll, "EXIT", new Runnable () {
            public void run () {
                ExitButtonClicked ();
            }
        });
        AddXMenuItem (xmll, "file transfer", new Runnable () {
            public void run () {
                currentsession.SetScreenMode (MySession.MSM_FILES);
            }
        });
        AddXMenuItem (xmll, "HELP", new Runnable () {
            public void run () {
                ShowHelpScreen ();
            }
        });
        AddXMenuItem (xmll, "internal log", new Runnable () {
            @Override
            public void run () {
                internalLogView.show ();
            }
        });
        AddXMenuItem (xmll, "known hosts", new Runnable () {
            public void run () {
                myhostkeyrepo.ShowKnownHostMenu ();
            }
        });
        AddXMenuItem (xmll, "local files only", new Runnable () {
            public void run () {
                ShowLocalFilesOnly ();
            }
        });
        AddXMenuItem (xmll, "local keypairs", new Runnable () {
            public void run () {
                new LocalKeyPairMenu (SshClient.this).ShowLocalKeyPairMenu ();
            }
        });
        AddXMenuItem (xmll, "master password", new Runnable () {
            public void run () {
                masterPassword.ShowMenu ();
            }
        });
        AddXMenuItem (xmll, "network interfaces", new Runnable () {
            public void run () {
                networkInterfacesView.show ();
            }
        });
        AddXMenuItem (xmll, "reconnect", new Runnable () {
            public void run () {
                ReconnectConfirm (currentsession);
            }
        });
        AddXMenuItem (xmll, "saved logins", new Runnable () {
            public void run () {
                savedlogins.ShowSavedLoginsMenu ();
            }
        });
        AddXMenuItem (xmll, "sessions", new Runnable () {
            public void run () {
                ShowSessionsMenu ();
            }
        });
        AddXMenuItem (xmll, "settings", new Runnable () {
            public void run () {
                settings.ShowMenu ();
            }
        });
        AddXMenuItem (xmll, "shell", new Runnable () {
            public void run () {
                currentsession.SetScreenMode (MySession.MSM_SHELL);
            }
        });
        AddXMenuItem (xmll, "tunnels", new Runnable () {
            public void run ()
            {
                currentsession.SetScreenMode (MySession.MSM_TUNNEL);
            }
        });
        AddXMenuItem (xmll, "VNC client", new Runnable () {
            public void run ()
            {
                currentsession.SetScreenMode (MySession.MSM_VNCCLI);
            }
        });

        ScrollView sv = new ScrollView (this);
        sv.addView (xmll);
        AlertDialog.Builder ab = new AlertDialog.Builder (this);
        ab.setView (sv);
        ab.setNegativeButton ("Cancel", null);
        extendedMenuAD = ab.create ();

        return super.onCreateOptionsMenu (menu);
    }

    public void SetDefaultMMenuItems ()
    {
        SetMMenuItems (
                "-", null,
                "-", null,
                "-", null,
                "-", null,
                "-", null);
    }

    public void SetMMenuItems (
            String ident1, Runnable runit1,
            String ident2, Runnable runit2,
            String ident3, Runnable runit3,
            String ident4, Runnable runit4,
            String ident5, Runnable runit5)
    {
        if (mainMenu != null) {
            Log.d (TAG, "SetMMenuItems: " + ident1 + " " + ident2 + " " + ident3 + " " + ident4 + " " + ident5);
            mainMenu.clear ();
            allMainMenuItems.clear ();
            AddMMenuItem (ident1, runit1);
            AddMMenuItem (ident2, runit2);
            AddMMenuItem (ident3, runit3);
            AddMMenuItem (ident4, runit4);
            AddMMenuItem (ident5, runit5);
            AddMMenuItem ("More >>", new Runnable () {
                public void run ()
                {
                    extendedMenuAD.show ();
                }
            });
        }
    }

    // add item to main (Android-provided) menu
    private void AddMMenuItem (String ident, Runnable runit)
    {
        mainMenu.add (ident);
        allMainMenuItems.put (ident, runit);
    }

    // add item to our extended (scrolled button list) menu
    private void AddXMenuItem (LinearLayout xmll, String ident, final Runnable runit)
    {
        Button xmb = MyButton ();
        xmb.setOnClickListener (new View.OnClickListener () {
            @Override
            public void onClick (View view) {
                extendedMenuAD.dismiss ();
                runit.run ();
            }
        });
        xmb.setText (ident);
        xmll.addView (xmb);
    }

    // This is called whenever the main Android-provided menu is opened by the user.
    // We want to make sure our extended (scrolled button list) menu is closed.
    @Override
    public boolean onMenuOpened (int featureId, Menu menu)
    {
        // if menu button pressed while asking for master password,
        // exit the app
        if (!masterPWGood) {
            finish ();
            return true;
        }

        extendedMenuAD.dismiss ();
        return super.onMenuOpened (featureId, menu);
    }

    // This is called when someone clicks on an item in the
    // main menu displayed by the hardware menu button.
    // We make sure our extended menu is closed then call the function.
    @Override
    public boolean onOptionsItemSelected (MenuItem menuItem)
    {
        CharSequence sel = menuItem.getTitle ();
        Runnable runit = allMainMenuItems.get (sel);
        if (runit != null) {
            extendedMenuAD.dismiss ();
            runit.run ();
            return true;
        }
        return super.onOptionsItemSelected (menuItem);
    }

    /**
     * Confirm disconnection/deletion of the given session.
     */
    private void DisconnectConfirm (final MySession ms)
    {
        AlertDialog.Builder ab = new AlertDialog.Builder (SshClient.this);
        ab.setTitle ("Confirm disconnect session");
        ab.setMessage (ms.GetSessionName ());
        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton)
            {
                // drop TCP connection and kill thread
                ms.Disconnect ();

                // remove session from list of all sessions
                for (Iterator<MySession> it = allsessions.iterator (); it.hasNext ();) {
                    MySession s = it.next ();
                    if (s == ms) it.remove ();
                }

                // remove any session-related pages from backActionStack
                for (Iterator<BackAction> it = backActionStack.iterator (); it.hasNext ();) {
                    BackAction ba = it.next ();
                    if (ba.session () == ms) it.remove ();
                }

                // pop back to whatever is left on stack
                // or if empty, create a new session
                if (!popBackAction ()) {
                    MySession s = new MySession (SshClient.this);
                    allsessions.addLast (s);
                    s.ShowScreen ();
                }
            }
        });
        ab.setNegativeButton ("Cancel", null);
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
                ShutdownEverything ();
            }
        });
        ab.setNegativeButton ("Cancel", null);
        ab.show ();
    }

    /**
     * Confirm reconnection of the given session.
     */
    private void ReconnectConfirm (final MySession ms)
    {
        AlertDialog.Builder ab = new AlertDialog.Builder (SshClient.this);
        ab.setTitle ("Confirm reconnect session");
        ab.setMessage (ms.GetSessionName ());
        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton)
            {
                // drop TCP connection and kill thread
                ms.Disconnect ();

                // restart the connection
                ms.StartConnecting ();
            }
        });
        ab.setNegativeButton ("Cancel", null);
        ab.show ();
    }

    private void ShutdownEverything ()
    {
        for (MySession ms : allsessions) {
            ms.Disconnect ();
        }
        finish ();
    }

    // open screen to explore local filesystem without having to be connected
    private void ShowLocalFilesOnly ()
    {
        // create the file viewer if the first time here
        if (localFilesOnly == null) {

            // make two local file navigator screens
            FileExplorerNav lcl1 = new MySession.MyFENav (this, "local-1");
            FileExplorerNav lcl2 = new MySession.MyFENav (this, "local-2");

            // wrap them in a file viewer screen
            localFilesOnly = new MySession.MyFEView (this);
            localFilesOnly.addFileNavigator (lcl1);
            localFilesOnly.addFileNavigator (lcl2);

            // set both current directories to the cache directory
            lcl1.setCurrentDir (GetLocalDir ());
            lcl2.setCurrentDir (GetLocalDir ());

            // start with local-1
            localFilesOnly.setCurrentFileNavigator (lcl1);
        }

        // display the file viewer screen
        setContentView (localFilesOnly);

        // set up how to pop back to this screen
        pushBackAction (new BackAction () {

            // it is always OK to pop it off with the back button
            @Override
            public boolean okToPop () {
                return true;
            }

            // this is how to redisplay when the back button pops to it
            @Override
            public void reshow () {
                ShowLocalFilesOnly ();
            }

            // make sure there is only one of these in the back stack
            @Override
            public String name () {
                return "localfilesonly";
            }

            // it does not have any session association
            @Override
            public MySession session () {
                return null;
            }
        });
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
                DisconnectConfirm (ms);
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
        ab.setNegativeButton ("Cancel", null);

        currentMenuDialog = ab.show ();
    }

    /**
     * Display the help text from the help.html file.
     */
    private void ShowHelpScreen ()
    {
        // make sure we have a view that displays the help web page stuff
        if (helpView == null) helpView = new HelpView (this);

        // display whatever is on the help page
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_USER);
        setContentView (helpView);

        pushBackAction (new BackAction () {
            @Override
            public boolean okToPop () {
                // if webview doesn't have a page to go back to
                // then it is ok to pop the help page off
                if (!helpView.canGoBack ()) {
                    return true;
                }
                // webview has a page to go back to, tell it to back up a page
                // then don't pop the webview off the stack as it is still current
                helpView.goBack ();
                return false;
            }
            @Override
            public void reshow () {
                ShowHelpScreen ();
            }
            @Override
            public String name () {
                return "help";
            }
            @Override
            public MySession session () {
                return null;
            }
        });
    }

    /*************************\
     *  Permission Requests  *
    \*************************/
    private HashMap<Integer,Runnable> permissionRequests = new HashMap<> ();
    private int permreqseq;

    public boolean requestExternalStorageAccess (@NonNull Runnable callback)
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager ()) return true;
            int seq = ++ permreqseq;
            permissionRequests.put (seq, callback);
            Log.i (TAG, "requestExternalStorageAccess:R seq=" + seq);
            try {
                Intent intent = new Intent (android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + BuildConfig.APPLICATION_ID));
                this.startActivityForResult (intent, seq);
                return false;
            } catch (Exception e) {
                Log.e (TAG, "unable to request ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", e);
                try {
                    Intent intent = new Intent (android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    this.startActivityForResult (intent, seq);
                    return false;
                } catch (Exception e2) {
                    Log.e (TAG, "unable to request ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION", e2);
                }
            }
            permissionRequests.remove (seq);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission (Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                int seq = ++ permreqseq;
                permissionRequests.put (seq, callback);
                Log.i (TAG, "requestExternalStorageAccess:M seq=" + seq);
                // https://developer.android.com/reference/android/app/Activity#requestPermissions(java.lang.String[],%20int)
                requestPermissions (new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, seq);
                return false;
            }
        }

        return true;
    }

    @Override
    public void onRequestPermissionsResult (int requestcode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        for (int i = 0; (i < permissions.length) && (i < grantResults.length); i ++) {
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                Runnable runit = permissionRequests.remove (requestcode);
                if (runit != null) {
                    runit.run ();
                }
            }
        }
    }

    @Override
    protected void onActivityResult (int requestCode, int resultCode, Intent data)
    {
        Log.i (TAG, "onActivityResult: request=" + requestCode + " result=" + resultCode);
        Runnable runit = permissionRequests.remove (requestCode);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if ((runit != null) && Environment.isExternalStorageManager ()) {
                runit.run ();
            }
        }
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
        v.setTextColor (Color.WHITE);
        v.setTextSize (UNIFORM_TEXT_SIZE);
        return v;
    }

    /***************\
     *  Utilities  *
    \***************/

    /**
     * Display an error alert dialog box, or really just anything that just
     * needs a simple OK button and doesn't need to wait for a response.
     */
    public void ErrorAlert (String title, String message)
    {
        AlertDialog.Builder ab = new AlertDialog.Builder (this);
        ab.setTitle (title);
        ab.setMessage (message);
        ab.setPositiveButton ("OK", null);
        ab.show ();
    }

    /**
     * Get exception message for dialog boxes etc.
     */
    public static String GetExMsg (Throwable e)
    {
        String msg = e.getClass ().getSimpleName ();
        String txt = e.getMessage ();
        if ((txt != null) && !txt.equals ("")) msg += ": " + txt;
        return msg;
    }

    /**
     * Get directory on local filesystem for temp files.
     */
    public IFile GetLocalDir ()
    {
        File lcldir = getExternalCacheDir ();
        if (lcldir == null) lcldir = getCacheDir ();
        return new FileIFile (this, lcldir);
    }

    /**
     * Make beep sound.
     */
    public void MakeBeepSound ()
    {
        if (!settings.dont_beep.GetValue ()) {
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
    }

    /**
     * Paste contents of client clipboard to host screen clipboard.
     */
    public interface PFC { void run (String str); }
    public void PasteFromClipboard (final PFC done)
    {
        final AlertDialog.Builder ab = new AlertDialog.Builder (this);
        ab.setTitle ("Paste clipboard to host");
        ab.setPositiveButton ("Internal", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton) {
                done.run (internalClipboard);
            }
        });
        final ClipboardManager cbm = (ClipboardManager) getSystemService (Context.CLIPBOARD_SERVICE);
        if (cbm != null) {
            ab.setNeutralButton ("External", new DialogInterface.OnClickListener () {
                public void onClick (DialogInterface dialog, int whichButton) {
                    done.run (cbm.getText ().toString ());
                }
            });
        }
        ab.setNegativeButton ("Cancel", null);
        ab.show ();
    }

    /**
     * Maybe send the selected character sequence to the clipboard.
     */
    public void CopyToClipboard (final String str, final Runnable done)
    {
        // start making an alert box
        AlertDialog.Builder ab = new AlertDialog.Builder (this);
        ab.setTitle ("Copy to clipboard?");

        // its message is the selected string
        int len = str.length ();
        StringBuilder msg = new StringBuilder (len);
        if (len <= 50) {
            AppendSanitized (msg, str, 0, len);
        } else {
            AppendSanitized (msg, str, 0, 24);
            msg.append ("...");
            AppendSanitized (msg, str, len - 24, len);
        }
        ab.setMessage (msg.toString ());

        // Internal button does the copy then deselects string
        ab.setPositiveButton ("Internal", new DialogInterface.OnClickListener () {
            public void onClick (DialogInterface dialog, int whichButton) {
                internalClipboard = str;
                if (done != null) done.run ();
            }
        });

        // External button does the copy then deselects string
        final ClipboardManager cbm = (ClipboardManager) getSystemService (Context.CLIPBOARD_SERVICE);
        if (cbm != null) {
            ab.setNeutralButton ("External", new DialogInterface.OnClickListener () {
                public void onClick (DialogInterface dialog, int whichButton) {
                    cbm.setText (str);
                    if (done != null) done.run ();
                }
            });
        }

        // Cancel button just leaves everything as is
        ab.setNegativeButton ("Cancel", null);

        // display the dialog box
        ab.show ();
    }

    /**
     * Make sure there are no newlines in the text for the message box.
     * That should be the only control character in the given text.
     */
    private static void AppendSanitized (StringBuilder msg, CharSequence buf, int beg, int end)
    {
        for (int i = beg; i < end; i ++) {
            char c = buf.charAt (i);
            if (c == '\n') msg.append ("\\n");
            else if (c == '\\') msg.append ("\\\\");
            else msg.append (c);
        }
    }
}
