/**
 * Overall controller for exploring files.
 * Wraps around multiple FileExplorerNav's to navigate and transfer files among them.
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
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;

@SuppressLint({ "SetTextI18n", "ViewConstructor" })
public class FileExplorerView extends LinearLayout {
    public final static String TAG = "SshClient";

    public static class Selected extends AsyncFileTasks.Selected {
        public FileExplorerNav nav;  // what navigator the file was selected with

        public Selected (IFile file, FileExplorerNav nav)
        {
            this.file = file;
            this.nav  = nav;
        }

        // file pointed to by symlinks (or original file if not a symlink)
        private boolean symlinkKnown;
        private IFile symlinkValue;
        public IFile symlink () throws IOException
        {
            if (!symlinkKnown) {
                symlinkKnown = true;
                symlinkValue = file.getTarget ();
            }
            return symlinkValue;
        }

        // the file is readable
        private boolean readableKnown;
        private boolean readableValue;
        public boolean readable () throws IOException
        {
            if (!readableKnown) {
                readableKnown = true;
                readableValue = (symlink () != null) && symlink ().canRead ();
            }
            return readableValue;
        }

        // the file's directory is writable
        private boolean parwriteKnown;
        private boolean parwriteValue;
        public boolean parwrite () throws IOException
        {
            if (!parwriteKnown) {
                parwriteKnown = true;
                parwriteValue = parent ().canWrite ();
            }
            return parwriteValue;
        }

        // the file's directory
        private IFile parentValue;
        public IFile parent ()
        {
            if (parentValue == null) {
                parentValue = file.getParentFile ();
            }
            return parentValue;
        }
    }

    public HashMap<String,Object> savestate = new HashMap<> ();

    private AlertDialog currentMenuDialog;
    private AllSelectedFiles allSelectedFiles;                  // list of files that are currently selected
    private Button navLeftBut;                                  // goes leftward in fileExplorerNavList
    private Button navRiteBut;                                  // goes rightward in fileExplorerNavList
    private FileExplorerNav currentNav;                         // currently displayed nav window
    private FileExplorerNav localCacheNav;                      // nav window that holds local cache directory
    private HorizontalScrollView selectedFilesViewSV;           // selectedFilesView scroller
    private int xfersRunning;                                   // number of transfers in progress
    private IFile localCacheDir;                                // directory within localCacheNav that can hold temp files
    private LinearLayout funcButtonRowLL;                       // currently displayed function buttons
    private LinearLayout mainScrollerLL;                        // holds everything except function buttons
    private LinkedList<FileExplorerNav> fileExplorerNavList;    // list of all navigators we can switch to
    private LinkedList<Runnable> noXfersQueue = new LinkedList<> ();
    private LinkedList<PDiagdFileTasks.CopyMoveDelTask> pdiagdFileTasks = new LinkedList<> ();
    private ScrollView mainScrollerSV;
    private SshClient sshclient;                                // app context
    private TextView leftSpacer;                                // spacing in function buttons
    private TextView riteSpacer;                                // spacing in function buttons
    private TextView selectedFilesView;                         // shows currently selected files

    public FileExplorerView (SshClient sc)
    {
        super (sc);
        sshclient = sc;

        fileExplorerNavList = new LinkedList<> ();
        allSelectedFiles    = new AllSelectedFiles ();

        setOrientation (LinearLayout.VERTICAL);

        funcButtonRowLL = new LinearLayout (sshclient);
        funcButtonRowLL.setOrientation (LinearLayout.HORIZONTAL);
        HorizontalScrollView funcButtonRowSV = new HorizontalScrollView (sshclient);
        funcButtonRowSV.addView (funcButtonRowLL);

        navLeftBut = sshclient.MyButton ();
        navLeftBut.setOnClickListener (new View.OnClickListener () {
            public void onClick (View v) {
                FileExplorerNav prevnav = null;
                for (FileExplorerNav thisnav : fileExplorerNavList) {
                    if (thisnav == currentNav) break;
                    prevnav = thisnav;
                }
                if ((prevnav != null) && !hasXfersRunning (true)) {
                    setCurrentFileNavigator (prevnav);
                }
            }
        });

        leftSpacer = sshclient.MyTextView ();
        leftSpacer.setText ("    ");

        riteSpacer = sshclient.MyTextView ();
        riteSpacer.setText ("    ");

        selectedFilesView = sshclient.MyTextView ();
        selectedFilesView.setHorizontallyScrolling (true);
        selectedFilesView.setTypeface (Typeface.MONOSPACE);
        selectedFilesViewSV = new HorizontalScrollView (sshclient);
        selectedFilesViewSV.addView (selectedFilesView);

        navRiteBut = sshclient.MyButton ();
        navRiteBut.setOnClickListener (new View.OnClickListener () {
            public void onClick (View v) {
                FileExplorerNav prevnav = null;
                for (FileExplorerNav thisnav : fileExplorerNavList) {
                    if (prevnav == currentNav) {
                        if (!hasXfersRunning (true)) {
                            setCurrentFileNavigator (thisnav);
                        }
                        break;
                    }
                    prevnav = thisnav;
                }
            }
        });

        mainScrollerLL = new LinearLayout (sshclient);
        mainScrollerLL.setOrientation (LinearLayout.VERTICAL);
        mainScrollerSV = new ScrollView (sshclient);
        mainScrollerSV.addView (mainScrollerLL);

        addView (funcButtonRowSV);
        addView (mainScrollerSV);

        // get <<no files selected>> message on screen
        allSelectedFiles.clear ();
    }

    /***************************************\
     *  Selecting current FileExplorerNav  *
    \***************************************/

    // add a navigator that we can switch to
    public void addFileNavigator (FileExplorerNav nav)
    {
        nav.setFileExplorerView (this);
        fileExplorerNavList.addLast (nav);
    }

    public void setCurrentFileNavigator (final FileExplorerNav nav)
    {
        // make sure the nav is part of our navs
        if (nav.getFileExplorerView () != this) {
            nav.setFileExplorerView (this);
            fileExplorerNavList.addLast (nav);
        }

        // stay on current screen if there is a transfer going
        // then switch when the transfer completes
        runWhenNoXfers (new Runnable () {
            @Override
            public void run ()
            {
                // set it as our current one
                currentNav = nav;
                savestate.put ("currentNav", nav.getDomain ());

                // rebuild our view to include the new current nav
                // tell it to rebuild its view and it will tell us to rebuild our buttons
                mainScrollerLL.removeAllViews ();
                for (PDiagdFileTasks.CopyMoveDelTask cmdt : pdiagdFileTasks) {
                    mainScrollerLL.addView (cmdt.currentGUI ());
                }
                mainScrollerLL.addView (selectedFilesViewSV);
                if (currentNav != null) {
                    mainScrollerLL.addView (currentNav);
                    currentNav.setCurrentDir (null);
                }
            }
        });
    }

    public void setLocalCacheFileNavigator (FileExplorerNav nav, IFile dir)
    {
        // make sure the nav is part of our navs
        if ((nav != null) && (nav.getFileExplorerView () != this)) {
            nav.setFileExplorerView (this);
            fileExplorerNavList.addLast (nav);
        }

        localCacheNav = nav;
        localCacheDir = dir;
    }

    public FileExplorerNav getCurrentFileNavigator ()
    {
        return currentNav;
    }

    public List<FileExplorerNav> getAllFileNavigators ()
    {
        return fileExplorerNavList;
    }

    public FileExplorerNav findNavByDomain (String domain)
    {
        for (FileExplorerNav fen : fileExplorerNavList) {
            if (fen.getDomain ().equals (domain)) return fen;
        }
        return null;
    }

    /**
     * Called externally to paste (write) the given contents to a file.
     */
    public void pasteClipToFile (final String cn, final byte[] clip)
    {
        final EditText name = sshclient.MyEditText ();
        name.setOnEditorActionListener (new TextView.OnEditorActionListener () {
            @Override
            public boolean onEditorAction (TextView textView, int i, KeyEvent keyEvent)
            {
                if ((i == EditorInfo.IME_ACTION_DONE) || (i == EditorInfo.IME_ACTION_NEXT)) {
                    currentMenuDialog.dismiss ();
                    pasteAuthorized (cn, clip, name);
                    return true;
                }
                return false;
            }
        });
        name.setSingleLine (true);

        AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Paste " + cn);
        ab.setMessage ("Enter filename to paste to");
        ab.setView (name);
        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
            @Override
            public void onClick (DialogInterface dialogInterface, int i)
            {
                pasteAuthorized (cn, clip, name);
            }
        });
        ab.setNegativeButton ("Cancel", null);
        currentMenuDialog = ab.show ();
    }

    private void pasteAuthorized (String cn, final byte[] clip, EditText name)
    {
        final IFile file = currentNav.getCurrentDir ().getChildFile (name.getText ().toString ());
        boolean exists;
        try {
            exists = file.exists ();
        } catch (IOException ioe) {
            Log.d (TAG, "exists exception " + file.getAbsolutePath (), ioe);
            sshclient.ErrorAlert ("Paste " + cn, SshClient.GetExMsg (ioe));
            return;
        }
        if (exists) {
            AlertDialog.Builder abo = new AlertDialog.Builder (sshclient);
            abo.setTitle ("Paste " + cn);
            abo.setMessage ("Overwrite " + file.getAbsolutePath ());
            abo.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                @Override
                public void onClick (DialogInterface dialogInterface, int i)
                {
                    writeClipToFile (file, clip);
                }
            });
            abo.setNegativeButton ("Cancel", null);
            abo.show ();
            return;
        }
        writeClipToFile (file, clip);
    }

    private void writeClipToFile (IFile file, byte[] clip)
    {
        try {
            OutputStream os = file.getOutputStream (IFile.OSMODE_CREATE);
            try {
                os.write (clip);
                os.flush ();
            } finally {
                os.close ();
            }
            currentNav.setCurrentDir (file.getParentFile ());
            selectFile (file, currentNav);
        } catch (IOException ioe) {
            Log.d (TAG, "file write error " + file.getAbsolutePath (), ioe);
            sshclient.ErrorAlert ("Error writing file", SshClient.GetExMsg (ioe));
        }
    }

    /**
     * Called externally to copy (read) a file into the given clip.
     */
    public byte[] copyClipFromFile (String cn)
    {
        if (allSelectedFiles.size () != 1) {
            sshclient.ErrorAlert ("Copy from " + cn, "Select exactly one file to copy to clipboard then try again");
            return null;
        }
        IFile file = allSelectedFiles.iterator ().next ().file;
        try {
            InputStream is = file.getInputStream ();
            try {
                LinkedList<byte[]> fullbufs = new LinkedList<> ();
                byte[] buf = new byte[4096];
                int ofs;
                while (true) {
                    int rc;
                    for (ofs = 0; ofs < buf.length; ofs += rc) {
                        rc = is.read (buf, ofs, buf.length - ofs);
                        if (rc < 0) break;
                    }
                    if (ofs < buf.length) break;
                    fullbufs.addLast (buf);
                    buf = new byte[buf.length];
                }
                int len = buf.length * fullbufs.size () + ofs;
                byte[] result = new byte[len];
                len = 0;
                for (byte[] fullbuf : fullbufs) {
                    System.arraycopy (fullbuf, 0, result, len, fullbuf.length);
                    len += fullbuf.length;
                }
                System.arraycopy (buf, 0, result, len, ofs);
                return result;
            } finally {
                is.close ();
            }
        } catch (IOException ioe) {
            Log.d (TAG, "file read error " + file.getAbsolutePath (), ioe);
            sshclient.ErrorAlert ("Error reading file ", SshClient.GetExMsg (ioe));
            return null;
        }
    }

    /************************************\
     *  Restore state on GUI re-attach  *
    \************************************/

    public void RestoreState (final HashMap<String,Object> festate)
    {
        // restore nav window current directories, local and remote
        // they will queue behind any copy-in-progress
        for (String key : festate.keySet ()) {
            if (key.startsWith ("nav:currentDir:")) {
                FileExplorerNav nav = findNavByDomain (key.substring (15));
                if (nav != null) nav.setCurrentDir ((IFile) festate.get (key));
            }
        }

        // set whichever of those nav windows up for display
        String dom = (String) festate.get ("currentNav");
        FileExplorerNav nav = findNavByDomain (dom);
        if (nav != null) setCurrentFileNavigator (nav);
        else Log.w (TAG, "FileExplorerView.RestoreState: can't find nav " + dom);

        // maybe there is a copy still in progress.
        // wait for other transfers (such as scanning directories) to complete first.
        runWhenNoXfers (new Runnable () {
            @Override
            public void run () {
                // now fill in copy/move/delete progress widgets
                DetachableCopyMoveDel.retachAll (FileExplorerView.this, festate);
            }
        });
    }

    /**
     * User clicked the Disconnect menu button for this session
     * or clicked the EXIT button for the whole app.
     * Shut down what needs to be shut down.
     */
    public void Disconnect ()
    {
        for (PDiagdFileTasks.CopyMoveDelTask cmdt : pdiagdFileTasks) {
            cmdt.abortabort ();
        }
    }

    /****************************\
     *  User action processing  *
    \****************************/

    /**
     * A directory/file has been double-clicked in the window.
     * Highlight the file then display the function buttons.
     */
    public void onFileSelected (IFile selected)
    {
        // can't be busy doing a transfer cuz we don't want selected list changing
        // and also cuz the SSH connection might be all clogged up
        if (!hasXfersRunning (true)) {

            // maybe just deselecting the file
            // otherwise select it
            if (!deselectFile (selected, currentNav)) {
                // wasn't already selected, so mark it selected now
                selectFile (selected, currentNav);
            }

            // re-draw the buttons because we have different stuff selected now
            setFuncButtonRow ();
        }
    }

    private boolean deselectFile (IFile file, FileExplorerNav nav)
    {
        boolean hit = false;
        for (Iterator<Selected> it = allSelectedFiles.iterator (); it.hasNext ();) {
            Selected sel = it.next ();
            if ((sel.nav == nav) && sel.file.equals (file)) {
                it.remove ();
                hit = true;
            }
        }
        return hit;
    }

    private void selectFile (IFile file, FileExplorerNav nav)
    {
        // add this file to list of selected files and highlight it
        Selected selent = new Selected (file, nav);
        allSelectedFiles.addLast (selent);
    }

    /**
     * The user wants to copy or move selected file(s) to the current directory.
     */
    private void onCopyMoveButtonClicked (final boolean move)
    {
        final IFile _hereDir = currentNav.getCurrentDir ();

        /*
         * Set up alert dialog box to let user confirm the copy/move and allow change of name.
         *   title     = "Move/Copy ... to ... "
         *   message   = not used
         *   example   = first and last selected files and where they get copied to
         *   nameBox   = something they can change name in
         *   flatCheck = flat mapping of multi-directory inputs to single directory output
         */
        AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ((move ? "Move" : "Copy") + " ... to ...");

        final TextView exampleBox = sshclient.MyTextView ();

        final EditText nameBox = sshclient.MyEditText ();
        nameBox.setSingleLine (true);
        nameBox.setTag (false);
        nameBox.setOnEditorActionListener (new TextView.OnEditorActionListener () {
            @Override
            public boolean onEditorAction (TextView textView, int i, KeyEvent keyEvent) {
                if ((i == EditorInfo.IME_ACTION_DONE) || (i == EditorInfo.IME_ACTION_NEXT)) {
                    currentMenuDialog.dismiss ();
                    // start copying/moving the files in a background thread
                    // with progress dialog box
                    // when finished, start displaying the updated copied-to directory
                    new DetachableCopyMove (
                            FileExplorerView.this,
                            move,
                            allSelectedFiles,
                            whenDoneRefreshDirectory);
                    return true;
                }
                return false;
            }
        });

        final CheckBox flatCheck = sshclient.MyCheckBox ();
        flatCheck.setText ("flatten file mapping");

        LinearLayout ll = new LinearLayout (sshclient);
        ll.setOrientation (LinearLayout.VERTICAL);
        ll.addView (exampleBox);
        ll.addView (nameBox);
        ll.addView (flatCheck);
        ScrollView sv = new ScrollView (sshclient);
        sv.addView (ll);
        ab.setView (sv);

        /*
         * Find string common to beginning of all selected input files.
         */
        final String hierComStr = findCommonOfAllSelecteds ();
        final int hierComLen = hierComStr.length ();

        /*
         * Starting name is the name part common to all input files.
         * Blank if the names have nothing in common.
         */
        nameBox.setText (hierComStr.substring (hierComStr.lastIndexOf ('/') + 1));

        /*
         * Flat checkbox enabled only if there is a '/' in the variant part of
         * at least one input file.
         */
        boolean flatEnable = false;
        for (Selected sel : allSelectedFiles) {
            String ap = sel.file.getAbsolutePath ();
            flatEnable |= (ap.indexOf ('/', hierComLen) >= 0);
        }
        if (!flatEnable) flatCheck.setVisibility (View.GONE);

        /*
         * Fill in initial file name mapping and the examples.
         */
        fillInCopyMoveMapping (exampleBox, hierComLen, flatCheck, nameBox, _hereDir);

        /*
         * Now that everything is defined, we can set up the listeners.
         */
        // flat-mapping checkbox changes redo the filename mapping
        // also flip the name string between flat and hierarchical if user hasn't changed it yet
        flatCheck.setOnClickListener (new OnClickListener () {
            @Override
            public void onClick (View view)
            {
                if (!(Boolean)nameBox.getTag ()) {
                    String comStr = hierComStr;
                    if (flatCheck.isChecked ()) {
                        int flatComLen = 0;
                        String flatComStr = null;
                        for (Selected sel : allSelectedFiles) {
                            String ap = sel.file.getAbsolutePath ();
                            int i = ap.lastIndexOf ('/');
                            String an = ap.substring (++ i);
                            if (flatComStr == null) {
                                flatComStr = an;
                                flatComLen = an.length ();
                            } else {
                                for (i = 0; (i < flatComLen) && (i < an.length ()); i ++) {
                                    if (flatComStr.charAt (i) != an.charAt (i)) break;
                                }
                                flatComLen = i;
                            }
                        }
                        if (flatComStr != null) comStr = flatComStr.substring (0, flatComLen);
                    }
                    nameBox.setText (comStr.substring (comStr.lastIndexOf ('/') + 1));
                }
                fillInCopyMoveMapping (exampleBox, hierComLen, flatCheck, nameBox, _hereDir);
            }
        });

        // name box changes redo the filename mapping
        nameBox.addTextChangedListener (new TextWatcher () {
            @Override
            public void beforeTextChanged (CharSequence charSequence, int i, int i2, int i3)
            { }
            @Override
            public void onTextChanged (CharSequence charSequence, int i, int i2, int i3)
            { }
            @Override
            public void afterTextChanged (Editable editable)
            {
                nameBox.setTag (true);
                fillInCopyMoveMapping (exampleBox, hierComLen, flatCheck, nameBox, _hereDir);
            }
        });

        // OK starts the copying/moving
        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
            @Override
            public void onClick (DialogInterface dialog, int which)
            {
                // start copying/moving the files in a background thread
                // with progress dialog box
                // when finished, start displaying the updated copied-to directory
                new DetachableCopyMove (
                        FileExplorerView.this,
                        move,
                        allSelectedFiles,
                        whenDoneRefreshDirectory);
            }
        });

        // Cancel leaves everything as is so they can change target directory.
        ab.setNegativeButton ("Cancel", null);

        /*
         * Finally show the dialog box.
         */
        currentMenuDialog = ab.show ();
    }

    /**
     * Figure out where all the files go and fill in a couple example strings.
     */
    private void fillInCopyMoveMapping (TextView exampleBox, int comLen, CheckBox flatCheck, EditText nameBox, IFile outDir)
    {
        /*
         * For hierarchical mapping, replace the common part of input filenames
         * with the supplied output directory + name.
         * For flat mapping, replace everything up to and including last '/' of input filenames
         * with the output directory + name.
         */
        boolean flatMap = flatCheck.isChecked ();
        String outName = nameBox.getText ().toString ();
        for (Selected sel : allSelectedFiles) {
            String oldAp = sel.file.getAbsolutePath ();
            if (flatMap) comLen = oldAp.lastIndexOf ('/') + 1;
            sel.outmap = outDir.getChildFile (outName + oldAp.substring (comLen));
        }

        /*
         * Display first and last selections as examples.
         */
        SpannableStringBuilder ssb = new SpannableStringBuilder ();
        Selected firstSel = null;
        Selected lastSel  = null;
        for (Selected sel : allSelectedFiles) {
            if (firstSel == null) firstSel = sel;
            lastSel = sel;
        }
        buildCopyMoveExample (ssb, firstSel);
        if (allSelectedFiles.size () > 2) {
            ssb.append ("\n  ...");
        }
        if (allSelectedFiles.size () > 1) {
            ssb.append ('\n');
            buildCopyMoveExample (ssb, lastSel);
        }
        exampleBox.setText (ssb);
    }

    private static void buildCopyMoveExample (SpannableStringBuilder ssb, Selected sel)
    {
        if (sel == null) return;

        // get both absolute paths without '/' on end
        // because the output wouldn't be correctly
        // marked as a directory as it doesn't exist
        String oldAp = sel.file.getAbsolutePath ();
        String newAp = sel.outmap.getAbsolutePath ();

        // output the basic strings to the buffer
        // remember where each one starts
        int oldApBeg = ssb.length ();
        ssb.append (oldAp);
        ssb.append (" ->\n    ");
        int newApBeg = ssb.length ();
        ssb.append (newAp);

        // find how many chars on end of each string are the same
        char lastMatchedChar = 0;
        int oldApLen = oldAp.length ();
        int newApLen = newAp.length ();
        int i;
        for (i = 0; (i < oldApLen) && (i < newApLen); i ++) {
            char oldChar = oldAp.charAt (oldApLen - i - 1);
            char newChar = newAp.charAt (newApLen - i - 1);
            if (oldChar != newChar) break;
            lastMatchedChar = oldChar;
        }
        if (lastMatchedChar == '/') -- i;

        // mark the first few chars of old string red
        // cuz that's what is going away
        ForegroundColorSpan oldColor = new ForegroundColorSpan (Color.RED);
        ssb.setSpan (oldColor, oldApBeg, oldApBeg + oldApLen - i, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        // mark the first few chars of new string green
        // cuz that's what is replacing the red ones
        ForegroundColorSpan newColor = new ForegroundColorSpan (Color.GREEN);
        ssb.setSpan (newColor, newApBeg, newApBeg + newApLen - i, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * User wants to delete the selected files.
     */
    private void onDeleteButtonClicked ()
    {
        String first   = null;
        String second  = null;
        String last    = null;
        for (Selected sel : allSelectedFiles) {
            String abspath = sel.file.getAPWithSlashNX ();
            if (abspath.endsWith ("/")) abspath += "...";
            if (first == null) first = abspath;
            else if (second == null) second = abspath;
            else last = abspath;
        }
        String msg = first;
        if (second != null) msg += "\n" + second;
        if (allSelectedFiles.size () > 3) msg += "\n  ...";
        if (last != null) msg += "\n" + last;

        AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Delete");
        ab.setMessage (msg);
        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
            @Override
            public void onClick (DialogInterface dialog, int which)
            {
                if (allSelectedFiles.size () > 1) {
                    AlertDialog.Builder abm = new AlertDialog.Builder (sshclient);
                    abm.setTitle ("Delete Multiple");
                    abm.setMessage ("Confirm you want to delete MULTIPLE files");
                    abm.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
                        @Override
                        public void onClick (DialogInterface dialogInterface, int i)
                        {
                            new DetachableDelete (FileExplorerView.this);
                        }
                    });
                    abm.setNegativeButton ("Cancel", null);
                    abm.show ();
                } else {
                    new DetachableDelete (FileExplorerView.this);
                }
            }
        });
        ab.setNegativeButton ("Cancel", null);
        ab.show ();
    }

    /**
     * User wants to open a file using an activity based on MIME type.
     * This function can be overridden to do something different when open button is clicked.
     * This default implementation attempts to open the file using the MIME type.
     */
    private void onOpenButtonClicked (IFile file)
    {
        final ActivityRunner actrun = new ActivityRunner ();
        actrun.file = file;

        try {
            actrun.uri = file.getUri ();
            actrun.mimeType = URLConnection.guessContentTypeFromName (actrun.uri.toString ());
            if ((actrun.mimeType == null) || actrun.mimeType.equals ("")) {
                try {
                    InputStream is = file.getInputStream ();
                    try {
                        actrun.mimeType = URLConnection.guessContentTypeFromStream (is);
                        if ((actrun.mimeType == null) || actrun.mimeType.equals ("")) {
                            AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
                            ab.setTitle ("No mime type found for file");
                            ab.setMessage (actrun.uri.toString ());
                            ab.setPositiveButton ("Try as plain text", new DialogInterface.OnClickListener () {
                                @Override
                                public void onClick (DialogInterface dialogInterface, int i)
                                {
                                    actrun.mimeType = "text/plain";
                                    actrun.tryOpening (FileExplorerView.this);
                                }
                            });
                            ab.setNegativeButton ("Cancel", null);
                            ab.show ();
                            return;
                        }
                    } finally {
                        try { is.close (); } catch (IOException ignored) { }
                    }
                } catch (IOException ioe) {
                    Log.w (TAG, "error getting mime type of " + file.getAbsolutePath (), ioe);
                    throw ioe;
                }
            }
        } catch (Exception e) {
            Log.w (TAG, "exception opening " + file.getAbsolutePath (), e);
            sshclient.ErrorAlert ("File open error", SshClient.GetExMsg (e));
            return;
        }

        actrun.tryOpening (this);
    }

    /**
     * Opens the file by running an activity.
     * Copies to local temp directory if necessary.
     * Must not have any GUI references in instance variables cuz it
     * can be saved by JSessionService if GUI is detached when the
     * copy to local temp directory is going.
     */
    private static class ActivityRunner implements DetachableCopyMove.WhenDone {
        public IFile file;
        public String mimeType;
        public Uri uri;

        public void tryOpening (FileExplorerView fev)
        {
            if (file instanceof FileIFile) {

                // file is on local filesystem, open it directly
                openLocalFile (fev);
            } else {

                // file is on remote filesystem, remember where to copy from
                AsyncFileTasks.Selected sel = new AsyncFileTasks.Selected ();
                sel.file = file;

                // find file navigator for the local filesystem
                if (fev.localCacheNav == null) {
                    fev.sshclient.ErrorAlert ("File open error", "no local cache nav to copy it to");
                    return;
                }

                // make up a temp file name
                String tmpName = "____TEMP____" + file.getName ();

                // set up file and URI to open the temp file
                file = fev.localCacheDir.getChildFile (tmpName);
                uri  = file.getUri ();

                // set up to copy remote file to the temp file in the background
                // .. then call finished() to open it
                sel.outmap = file;
                LinkedList<AsyncFileTasks.Selected> sels = new LinkedList<> ();
                sels.addLast (sel);
                new DetachableCopyMove (
                        fev,    // current GUI
                        false,  // copy (not move)
                        sels,   // files to be copied
                        this);  // call our whenDone() when done
            }
        }

        /**
         * Copy to local temp directory has completed, good or bad.
         */
        @Override  // DetachableCopyMove.WhenDone
        public void whenDone (FileExplorerView fev, Exception e)
        {
            // display the temp filesystem and its directory
            // so user can see it to move or delete it afterward
            fev.setCurrentFileNavigator (fev.localCacheNav);
            fev.localCacheNav.setCurrentDir (fev.localCacheDir);

            // check for successful copy
            if (e == null) {

                // open the temp local file
                openLocalFile (fev);
            }
        }

        /**
         * Open file (hopefully now on local filesystem) by spawning activity.
         */
        private void openLocalFile (FileExplorerView fev)
        {
            if (mimeType == null) {
                AlertDialog.Builder ab = new AlertDialog.Builder (fev.sshclient);
                ab.setTitle ("Unable to determine mime type");
                tryAsPlainText (fev, ab);
            } else {
                Intent newIntent = new Intent (Intent.ACTION_VIEW);
                newIntent.setDataAndType (uri, mimeType);
                newIntent.setFlags (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    fev.sshclient.startActivity (newIntent);
                } catch (ActivityNotFoundException anfe) {
                    Log.w (TAG, "activity not found for mime type " + mimeType, anfe);
                    AlertDialog.Builder ab = new AlertDialog.Builder (fev.sshclient);
                    ab.setTitle ("Activity not found for " + mimeType);
                    ab.setMessage (SshClient.GetExMsg (anfe));
                    if (!mimeType.equals ("text/plain")) {
                        tryAsPlainText (fev, ab);
                    } else {
                        ab.setNegativeButton ("OK", null);
                        ab.show ();
                    }
                }
            }
        }

        private void tryAsPlainText (final FileExplorerView fev, AlertDialog.Builder ab)
        {
            ab.setPositiveButton ("Try as plain text", new DialogInterface.OnClickListener () {
                @Override
                public void onClick (DialogInterface dialogInterface, int i)
                {
                    ActivityRunner.this.mimeType = "text/plain";
                    ActivityRunner.this.openLocalFile (fev);
                }
            });
            ab.setNegativeButton ("Cancel", null);
            ab.show ();
        }
    }

    /**
     * User has clicked the mkdir button to create a directory.
     * @param parFile = parent of directory to create
     */
    private void onMkDirButtonClicked (final IFile parFile)
    {
        currentNav.clearHighlightedFiles ();

        final EditText dirName = sshclient.MyEditText ();
        dirName.setSingleLine (true);
        dirName.setOnEditorActionListener (new TextView.OnEditorActionListener () {
            @Override
            public boolean onEditorAction (TextView textView, int i, KeyEvent keyEvent)
            {
                if ((i == EditorInfo.IME_ACTION_DONE) || (i == EditorInfo.IME_ACTION_NEXT)) {
                    currentMenuDialog.dismiss ();
                    mkDirAuthorized (parFile, dirName);
                    return true;
                }
                return false;
            }
        });

        AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Make Dir");
        ab.setMessage ("Enter dir name to make");
        ab.setView (dirName);
        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
            @Override
            public void onClick (DialogInterface dialog, int which)
            {
                mkDirAuthorized (parFile, dirName);
            }
        });
        ab.setNegativeButton ("Cancel", null);
        currentMenuDialog = ab.show ();
    }

    private void mkDirAuthorized (IFile parFile, TextView dirName)
    {
        IFile newDir = parFile.getChildFile (dirName.getText ().toString ());
        try {
            newDir.mkdirs ();
        } catch (IOException ioe) {
            Log.d (TAG, "mkdirs() error " + newDir.getAbsolutePath (), ioe);
            sshclient.ErrorAlert ("Failed to create directory", SshClient.GetExMsg (ioe));
            return;
        }
        currentNav.setCurrentDir (newDir);
    }

    /**
     * User clicked the Search button to search directories.
     */
    private void onSearchButtonClicked (final IFile dirFile)
    {
        currentNav.clearHighlightedFiles ();

        final CheckBox csCheck = sshclient.MyCheckBox ();
        csCheck.setText ("case sensitive");

        final EditText wcName = sshclient.MyEditText ();
        wcName.setSingleLine (true);
        wcName.setOnEditorActionListener (new TextView.OnEditorActionListener () {
            @Override
            public boolean onEditorAction (TextView textView, int i, KeyEvent keyEvent) {
                if ((i == EditorInfo.IME_ACTION_DONE) || (i == EditorInfo.IME_ACTION_NEXT)) {
                    currentMenuDialog.dismiss ();
                    currentNav.initiateTreeSearch (dirFile, wcName.getText ().toString (), csCheck.isChecked ());
                    return true;
                }
                return false;
            }
        });

        LinearLayout llv = new LinearLayout (sshclient);
        llv.setOrientation (LinearLayout.VERTICAL);
        llv.addView (wcName);
        llv.addView (csCheck);

        AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
        ab.setTitle ("Search " + dirFile.getAbsolutePath ());
        ab.setMessage ("Enter (wildcard) name to search for");
        ab.setView (llv);
        ab.setPositiveButton ("OK", new DialogInterface.OnClickListener () {
            @Override
            public void onClick (DialogInterface dialog, int which)
            {
                currentNav.initiateTreeSearch (dirFile, wcName.getText ().toString (), csCheck.isChecked ());
            }
        });
        ab.setNegativeButton ("Cancel", null);
        currentMenuDialog = ab.show ();
    }

    /*****************************\
     *  Used by FileExplorerNav  *
    \*****************************/

    /**
     * Make sure all files in the list are marked as selected.
     * @param files = list of files to mark
     * @param nav   = navigator they were selected from
     */
    public void selectFiles (Collection<IFile> files, FileExplorerNav nav)
    {
        ArrayList<Selected> selents = new ArrayList<> (files.size());
        int i = 0;
        for (IFile file : files) {
            selents.add (i ++, new Selected (file, nav));
        }
        allSelectedFiles.addList (selents);
    }

    /**
     * Set up function button row suitable for whatever the current state is.
     */
    public void setFuncButtonRow ()
    {
        funcButtonRowLL.removeAllViews ();

        /*
         * Can't do anything if transfer in progress cuz jsession might get jammed.
         */
        if (hasXfersRunning (false)) return;

        /*
         * Make sure we have a nav window to examine.
         */
        if (currentNav == null) return;

        /*
         * Examine current directory and selected file properties.
         */
        final IFile dirfile = currentNav.getCurrentDir ();
        boolean dirwritable = true;
        try { dirwritable = dirfile.canWrite (); } catch (IOException ioe) {
            Log.d (TAG, "canWrite() error " + dirfile.getAbsolutePath (), ioe);
        }

        boolean allReadable = !allSelectedFiles.isEmpty ();
        for (Selected sel : allSelectedFiles) {
            try { allReadable &= sel.readable (); } catch (IOException ioe) {
                Log.d (TAG, "readable() error " + sel.file.getAbsolutePath (), ioe);
            }
        }

        boolean allParwrite = !allSelectedFiles.isEmpty ();
        for (Selected sel : allSelectedFiles) {
            try { allParwrite &= sel.parwrite (); } catch (IOException ioe) {
                Log.d (TAG, "parwrite() error " + sel.file.getAbsolutePath (), ioe);
            }
        }
        int numSelected = allSelectedFiles.size ();

        /*
         * Can navigate left if this is not the first nav in the list.
         */
        FileExplorerNav prevnav = null;
        for (FileExplorerNav thisnav : fileExplorerNavList) {
            if (thisnav == currentNav) break;
            prevnav = thisnav;
        }
        if (prevnav != null) {
            navLeftBut.setTag (prevnav);
            navLeftBut.setText (prevnav.getDomain () + " <");
            funcButtonRowLL.addView (navLeftBut);
            funcButtonRowLL.addView (leftSpacer);
        }

        /*
         * Can always search.
         */
        Button searchBut = sshclient.MyButton ();
        searchBut.setOnClickListener (new OnClickListener () {
            public void onClick (View v)
            {
                onSearchButtonClicked (dirfile);
            }
        });
        searchBut.setText ("search");
        funcButtonRowLL.addView (searchBut);

        /*
         * Can make a sub-directory if writable.
         */
        if (dirwritable) {
            Button mkDirBut = sshclient.MyButton ();
            mkDirBut.setOnClickListener (new View.OnClickListener () {
                public void onClick (View v)
                {
                    onMkDirButtonClicked (dirfile);
                }
            });
            mkDirBut.setText ("make dir");
            funcButtonRowLL.addView (mkDirBut);
        }

        /*
         * Clear button if anything is selected.
         */
        if (!allSelectedFiles.isEmpty ()) {
            Button clearBut = sshclient.MyButton ();
            clearBut.setOnClickListener (new View.OnClickListener () {
                public void onClick (View v)
                {
                    allSelectedFiles.clear ();
                    setFuncButtonRow ();
                }
            });
            clearBut.setText ("clear");
            funcButtonRowLL.addView (clearBut);
        }

        /*
         * Copy button if all selected files are readable and current directory is writable.
         */
        if (allReadable && dirwritable) {
            Button copyBut = sshclient.MyButton ();
            copyBut.setOnClickListener (new View.OnClickListener () {
                public void onClick (View v)
                {
                    onCopyMoveButtonClicked (false);
                }
            });
            copyBut.setText ("copy");
            funcButtonRowLL.addView (copyBut);
        }

        /*
         * Move button if all selected files are readable and parent directories are writable
         * and current directory is writable.
         */
        if (allReadable && allParwrite && dirwritable) {
            Button moveBut = sshclient.MyButton ();
            moveBut.setOnClickListener (new View.OnClickListener () {
                public void onClick (View v)
                {
                    onCopyMoveButtonClicked (true);
                }
            });
            moveBut.setText ("move");
            funcButtonRowLL.addView (moveBut);
        }

        /*
         * Delete button if directory is writable and all selected
         * from the current directory (to prevent accidental deletes).
         */
        if (allParwrite && (numSelected > 0)) {
            boolean canDel = true;
            for (Selected sel : allSelectedFiles) {
                canDel &= currentNav.isFileListed (sel.file);
            }
            if (canDel) {
                Button delBut = sshclient.MyButton ();
                delBut.setOnClickListener (new View.OnClickListener () {
                    public void onClick (View v)
                    {
                        onDeleteButtonClicked ();
                    }
                });
                delBut.setText ("delete");
                funcButtonRowLL.addView (delBut);
            }
        }

        /*
         * Open button if just one non-directory file and it is readable.
         */
        if (allReadable && (numSelected == 1)) {
            final Selected sel = allSelectedFiles.iterator ().next ();
            try {
                final IFile symlink = sel.symlink ();
                if (!symlink.isDirectory ()) {
                    Button openBut = sshclient.MyButton ();
                    openBut.setOnClickListener (new View.OnClickListener () {
                        public void onClick (View v)
                        {
                            onOpenButtonClicked (symlink);
                        }
                    });
                    openBut.setText ("open");
                    funcButtonRowLL.addView (openBut);
                }
            } catch (IOException ioe) {
                Log.d (TAG, "error getting symlink " + sel.file.getAbsolutePath (), ioe);
            }
        }

        /*
         * Also do open button if the last item selected is a readable directory
         * so the user can navigate into directories while gathering up a list.
         */
        if (numSelected > 0) {
            final Selected sel = allSelectedFiles.getLast ();
            try {
                final IFile symlink = (sel == null) ? null : sel.symlink ();
                if ((symlink != null) && symlink.exists () && symlink.isDirectory () && symlink.canRead ()) {
                    Button openBut = sshclient.MyButton ();
                    openBut.setOnClickListener (new View.OnClickListener () {
                        public void onClick (View v)
                        {
                            deselectFile (sel.file, sel.nav);
                            sel.nav.setCurrentDir (symlink);
                        }
                    });
                    openBut.setText ("opendir");
                    funcButtonRowLL.addView (openBut);
                }
            } catch (IOException ioe) {
                Log.d (TAG, "error getting symlink " + sel.file.getAbsolutePath (), ioe);
            }
        }

        /*
         * Also do open button if the last item selected is a readable zip file
         * so the user can navigate into zip files (just like directories) while
         * gathering up a list.
         */
        if (numSelected > 0) {
            final Selected sel = allSelectedFiles.getLast ();
            try {
                final IFile symlink = (sel == null) ? null : sel.symlink ();
                if ((symlink != null) && symlink.exists () && sel.readable () && MyZipFileIFile.isZip (symlink)) {
                    Button openBut = sshclient.MyButton ();
                    openBut.setOnClickListener (new View.OnClickListener () {
                        public void onClick (View v)
                        {
                            deselectFile (sel.file, sel.nav);
                            sel.nav.setCurrentDir (new MyZipFileIFile (symlink));
                        }
                    });
                    openBut.setText ("opendir");
                    funcButtonRowLL.addView (openBut);
                }
            } catch (IOException ioe) {
                Log.d (TAG, "error getting symlink " + sel.file.getAbsolutePath (), ioe);
            }
        }

        /*
         * Can navigate right if this is not the last in the list.
         */
        prevnav = null;
        for (FileExplorerNav thisnav : fileExplorerNavList) {
            if (prevnav == currentNav) {
                funcButtonRowLL.addView (riteSpacer);
                navRiteBut.setTag (thisnav);
                navRiteBut.setText ("> " + thisnav.getDomain ());
                funcButtonRowLL.addView (navRiteBut);
                break;
            }
            prevnav = thisnav;
        }
    }

    /**
     * Indicate the start or finish of an asynchronous transfer operation.
     * We only want to let one run at a time otherwise the SSH connection tends to jam up.
     */
    public void incXfersRunning (int inc)
    {
        xfersRunning += inc;
        if (xfersRunning < 0) {
            throw new RuntimeException ("xfersRunning is negative");
        }
        setFuncButtonRow ();
        while ((xfersRunning == 0) && !noXfersQueue.isEmpty ()) {
            Runnable r = noXfersQueue.removeFirst ();
            r.run ();
        }
    }

    /**
     * See uf any asynchronous transfers are being done.
     * Optionally display an alert box if there are.
     */
    public boolean hasXfersRunning (boolean alert)
    {
        if (xfersRunning == 0) return false;
        if (alert) {
            sshclient.ErrorAlert (
                    "Transfer in progress",
                    "Either cancel, pause, wait or click the HOME button to let it continue in background.  " +
                            "You can also switch to a different session or start a new one."
            );
        }
        return true;
    }

    /**
     * Call the supplied function when no asynchronous transfers are in progress.
     * It is up to the supplied function to call incXfersRunning() as needed.
     */
    public void runWhenNoXfers (Runnable r)
    {
        if (xfersRunning == 0) r.run ();
        else noXfersQueue.addLast (r);
    }

    public void offsetMainScrollerBy (int dy)
    {
        mainScrollerSV.scrollBy (0, dy);
    }

    /**************\
     *  Internal  *
    \**************/

    /**
     * Bundles up a background copy/move operation that we can attach/detach from.
     * The JSessionService will keep the copy/move going in the background even if the app is terminated.
     */
    private static class DetachableCopyMove extends DetachableCopyMoveDel {
        public DetachableCopyMove (FileExplorerView fev,
                                   boolean moveMode,
                                   Collection<? extends AsyncFileTasks.Selected> selecteds,
                                   WhenDone whenDone)
        {
            // save info we need to detach/retach/finish
            this.whenDone = whenDone;
            savestatekey = "detachablecopymove:" + SystemClock.uptimeMillis ();

            // block other things from starting that use the SSH connection
            // cuz this copy might jam them up
            fev.incXfersRunning (1);

            // put this in list of things JSessionService will preserve if app is terminated
            fev.savestate.put (savestatekey, this);

            // start the copy going in a thread
            int xfrProg = fev.sshclient.getSettings ().xfr_prog.GetValue ();
            cmdt = PDiagdFileTasks.copyMoveFiles (selecteds, moveMode, xfrProg);

            // attach a GUI to begin with so user can see progress
            guiAttach (fev);
        }
    }

    /**
     * Bundles up a background delete operation that we can attach/detach from.
     * The JSessionService will keep the copy going in the background even if the app is terminated.
     */
    private static class DetachableDelete extends DetachableCopyMoveDel {
        public DetachableDelete (FileExplorerView fev)
        {
            // save info we need to detach/retach/finish
            whenDone = whenDoneRefreshDirectory;
            savestatekey = "detachabledelete:" + SystemClock.uptimeMillis ();

            // block other things from hogging SSH connection
            fev.incXfersRunning (1);

            // put this in list of things JSessionService will preserve if app is terminated
            fev.savestate.put (savestatekey, this);

            // see if hierarchical progress display wanted or not
            int xfrProg = fev.sshclient.getSettings ().xfr_prog.GetValue ();

            // fork thread to delete files
            cmdt = PDiagdFileTasks.deleteFiles (fev.allSelectedFiles, xfrProg);

            // attach a GUI to begin with so user can see progress
            guiAttach (fev);
        }
    }



    /**
     * Bundles up a background copy/move/delete operation that we can attach/detach from.
     * The JSessionService will keep the copy/move going in the background even if the app is terminated.
     */
    private static abstract class DetachableCopyMoveDel implements PDiagdFileTasks.IFinished, ScreenDataThread.GUIDetach {

        // implementations cannot save GUI references in instance variables
        public interface WhenDone {
            void whenDone (FileExplorerView fev, Exception e);
        }

        // things preserved by service
        // cannot contain any GUI references on detach
        private boolean savePaused;                      // preserved on detach()
        protected PDiagdFileTasks.CopyMoveDelTask cmdt;  // gui stuff removed via detach()
        private View progressView;                       // current gui attached view, nulled on detach()
        protected WhenDone whenDone;                     // preserved on detach()
        protected String savestatekey;                   // preserved on detach()

        /**
         * Detach GUI from copy/move/delete in progress.
         */
        public void guiDetach ()
        {
            if (progressView != null) {
                FileExplorerView fev = (FileExplorerView) progressView.getTag ();
                fev.pdiagdFileTasks.remove (cmdt);
                fev.mainScrollerLL.removeView (progressView);
                progressView = null;
            }
            cmdt.deleteGUI ();
        }

        /**
         * Re-attach GUI to all copy/moves in progress in this viewer.
         */
        public static void retachAll (FileExplorerView fev, HashMap<String,Object> festate)
        {
            for (Object val : festate.values ()) {
                if (val instanceof DetachableCopyMoveDel) {
                    DetachableCopyMoveDel zhis = (DetachableCopyMoveDel) val;
                    fev.savestate.put (zhis.savestatekey, zhis);
                    if (!zhis.savePaused) fev.incXfersRunning (1);
                    zhis.guiAttach (fev);
                }
            }
        }

        /**
         * Attach GUI to this particular copy/move/delete to user can view its progress.
         */
        protected void guiAttach (FileExplorerView fev)
        {
            // build GUI element that displays progress
            progressView = cmdt.createGUI (fev.sshclient, this);

            // remember where we are putting it
            progressView.setTag (fev);

            // show it as part of the scrolling area
            fev.pdiagdFileTasks.addFirst (cmdt);
            fev.mainScrollerLL.addView (progressView, 0);
            fev.mainScrollerSV.smoothScrollTo (0, 0);
        }

        /**
         * Called when the pause/resume button is clicked.
         * @param paused = true: paused and transfer has actually stopped;
         *                false: just about to resume transfer
         */
        @Override  // PDiagFileTasks.IFinished
        public void paused (boolean paused)
        {
            if (savePaused != paused) {

                // pausing actually aborts the transfer, leaving the SSH connection usable
                // so say there are no transfers running whilst paused
                if (progressView != null) {
                    FileExplorerView fev = (FileExplorerView) progressView.getTag ();
                    fev.incXfersRunning (paused ? -1 : 1);
                }

                // remember whether or not we have xfer count incremented
                savePaused = paused;
            }
        }

        /**
         * Called when the copy/move has completed.
         * We are necessarily attached to a GUI at this point,
         * cuz PDiagdFileTasks holds off calling us until GUI
         * is attached.
         */
        @Override  // PDiagdFileTasks.IFinished
        public void finished (Exception e)
        {
            // get what FEV the copy is for
            View progress = cmdt.currentGUI ();
            FileExplorerView fev = (FileExplorerView) progress.getTag ();

            // remove progress display as the copy/move is complete
            fev.mainScrollerLL.removeView (progress);
            fev.pdiagdFileTasks.remove (cmdt);

            // we no longer need JSessionService to preserve us
            fev.savestate.remove (savestatekey);

            // allow other use of the SSH connection now
            if (!savePaused) fev.incXfersRunning (-1);

            // tell caller that we are done
            whenDone.whenDone (fev, e);
        }
    }

    /**
     * Refresh the current directory display when the copy/move/delete completes so
     * it will show any changes made by the copy/move/delete.
     */
    private static DetachableCopyMove.WhenDone whenDoneRefreshDirectory = new DetachableCopyMove.WhenDone () {
        @Override
        public void whenDone (FileExplorerView fev, Exception e)
        {
            // clear selected files if successful
            if (e == null) fev.allSelectedFiles.clear ();

            // refresh current screen contents in case copy/move/delete changed it
            fev.getCurrentFileNavigator ().setCurrentDir (null);
        }
    };

    /**
     * Find out what is common to the beginning of all input file strings.
     */
    private String findCommonOfAllSelecteds ()
    {
        String comStr = null;
        int comLen = 0;
        for (Selected sel : allSelectedFiles) {
            String oldAp = sel.file.getAbsolutePath ();
            if (comStr == null) {
                comStr = oldAp;
                comLen = oldAp.length ();
            } else {
                int i;
                for (i = 0; (i < comLen) && (i < oldAp.length ()); i ++) {
                    if (comStr.charAt (i) != oldAp.charAt (i)) break;
                }
                comLen = i;
            }
        }
        return (comStr == null) ? "" : comStr.substring (0, comLen);
    }

    /**
     * List of all selected files.
     * Automatically (un-)highlights on add or remove,
     * and updates the selectedFilesView.
     */
    private class AllSelectedFiles implements Collection<Selected> {
        private int oldNLines;
        private String lastAdded;
        private TreeMap<String,Selected> list = new TreeMap<> ();

        // save key of last one added to list
        public void addLast (Selected sel)
        {
            lastAdded = makeKey (sel);
            list.put (lastAdded, sel);
            sel.nav.setFileHighlighted (sel.file, true);
            reloadSelectedFilesView ();
        }

        // add list of files to the end of the list, ignoring duplicate adds
        public void addList (Collection<Selected> objects)
        {
            for (Selected sel : objects) {
                String key = makeKey (sel);
                if (!list.containsKey (key)) {
                    list.put (key, sel);
                    sel.nav.setFileHighlighted (sel.file, true);
                }
            }
            reloadSelectedFilesView ();
        }

        public Selected getFirst ()
        {
            throw new UnsupportedOperationException ();
        }

        // get last one added to the list
        public Selected getLast ()
        {
            return (lastAdded == null) ? null : list.get (lastAdded);
        }

        @Override public boolean add (Selected object)
        {
            throw new UnsupportedOperationException ();
        }

        @Override public boolean addAll (@NonNull Collection<? extends Selected> collection)
        {
            throw new UnsupportedOperationException ();
        }

        @Override public void clear ()
        {
            for (Selected sel : list.values ()) {
                sel.nav.setFileHighlighted (sel.file, false);
            }
            list.clear ();
            lastAdded = null;
            reloadSelectedFilesView ();
        }

        @Override public boolean contains (Object object)
        {
            //noinspection SuspiciousMethodCalls
            return list.containsValue (object);
        }

        @Override public boolean containsAll (@NonNull Collection<?> collection)
        {
            throw new UnsupportedOperationException ();
        }

        @Override public boolean isEmpty ()
        {
            return list.isEmpty ();
        }

        @Override public @NonNull Iterator<Selected> iterator ()
        {
            return new ASFIterator ();
        }

        @Override public boolean remove (Object object)
        {
            throw new UnsupportedOperationException ();
        }

        @Override public boolean removeAll (@NonNull Collection<?> collection)
        {
            throw new UnsupportedOperationException ();
        }

        @Override public boolean retainAll (@NonNull Collection<?> collection)
        {
            throw new UnsupportedOperationException ();
        }

        @Override public int size ()
        {
            return list.size ();
        }

        @Override
        public @NonNull <T> T[] toArray (@NonNull T[] array)
        {
            //noinspection RedundantCast
            return (T[]) list.values ().toArray (array);
        }

        @Override
        public @NonNull Object[] toArray ()
        {
            throw new UnsupportedOperationException ();
        }

        private void reloadSelectedFilesView ()
        {
            StringBuilder sb = new StringBuilder ();
            for (String key : list.keySet ()) {
                if (sb.length () > 0) sb.append ('\n');
                sb.append (key);
            }
            int nLines = list.size ();
            if (nLines == 0) {
                sb.append ("<<no files selected>>");
                nLines ++;
            }
            selectedFilesView.setText (sb);

            // keep the directory listing area scrolled to the same item
            // so it doesn't appear to jump around as the user selects/
            // deselects files
            if (oldNLines != nLines) {
                int dy = (nLines - oldNLines) * selectedFilesView.getLineHeight ();
                mainScrollerSV.scrollBy (0, dy);
                oldNLines = nLines;
            }

            // without this, single selection gets chopped off
            // at length of '<<no files selected>>'
            selectedFilesView.requestLayout ();
        }

        private String makeKey (Selected sel)
        {
            return sel.nav.getDomain () + ":" + sel.file.getAbsolutePath ();
        }

        private class ASFIterator implements Iterator<Selected> {
            private Iterator<Selected> it = list.values ().iterator ();
            private Selected cur;

            public boolean hasNext ()
            {
                return it.hasNext ();
            }

            public Selected next ()
            {
                return cur = it.next ();
            }

            public void remove ()
            {
                it.remove ();
                cur.nav.setFileHighlighted (cur.file, false);
                reloadSelectedFilesView ();
                cur = null;
                lastAdded = null;
            }
        }
    }
}
