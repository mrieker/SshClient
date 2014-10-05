/**
 * Perform file tasks in background threads, providing detachable progress view.
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
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.format.Time;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Formatter;

public class PDiagdFileTasks {
    public final static String TAG = "SshClient";

    public final static int PROG_HIERARC = 1;  // 0: show datafiles only; 1: show hierarchical display
    public final static int PROG_PRESCAN = 2;  // 0: dirs just show entry count; 2: dirs show content totals

    // what of CopyMoveDelCallbacks we are making available to outsiders
    public interface CopyMoveDelTask {
        // create a GUI element that gets transfer progress updates
        // then calls finished when the transfer completes
        public View createGUI (Context ctx, IFinished finished);

        // don't post any progress updates to the GUI until createGUI() called again
        // and don't call finished() either until createGUI() called again
        public void deleteGUI ();

        // retrieve current GUI (or null if detached)
        public View currentGUI ();

        // abort it asap, being disconnected
        public void abortabort ();
    }

    public interface IFinished {
        // called with true when paused; false when resumed
        void paused (boolean paused);
        // called when the transfer has finished, good or bad
        void finished (Exception e);
    }

    /**
     * Start copying/moving a list of files.
     * @param selecteds = files to be copied/moved
     * @param moveMode = true: move files
     *                  false: copy files
     * @param xfrProg = PROG_ progress dialog style flags
     */
    public static CopyMoveDelTask copyMoveFiles (
            Collection<? extends AsyncFileTasks.Selected> selecteds,
            boolean moveMode,
            int xfrProg)
    {
        CopyMoveCallbacks callbacks = new CopyMoveCallbacks ();
        callbacks.moveMode = moveMode;
        callbacks.xfrProg  = xfrProg;
        callbacks.opcode   = moveMode ? "move" : "copy";
        callbacks.setSelecteds (selecteds);
        callbacks.startit ();
        return callbacks;
    }

    /**
     * GUI functions specific to copy/move.
     */
    private static class CopyMoveCallbacks extends CopyMoveDelCallbacks {
        public boolean moveMode;

        private AlertDialog adiag;
        private boolean copyExceptionAborted;
        private String pausedDuringOverwrite;

        /**
         * Start copying/moving the selected files.
         */
        @Override  // CopyMoveDelCallbacks
        protected AsyncFileTasks.CopyMoveDelFilesThread startup ()
        {
            return AsyncFileTasks.copyMoveFiles (selecteds, moveMode, (xfrProg & PROG_PRESCAN) != 0, this);
        }

        /**
         * About to start copying/moving a selection of files.
         * Set the dialog title to indicate which selection is being copyied/moved.
         * The message is used to identify which file of that selection is being done.
         */
        @Override  // CopyMoveDelCallbacks
        public void selectedStart (AsyncFileTasks.Selected selected)
        {
            super.selectedStart (selected);
            setTitle ("  " + selected.file.getAbsolutePath () +
                    "\nto\n  " + selected.outmap.getAbsolutePath ());
        }

        /**
         * A file is about to be overwritten by a copy/move.
         * Ax the user if it is ok or not.
         */
        @Override  // CopyMoveDelCallbacks
        public void overwrite (final IFile newFile, final AsyncFileTasks.IOverAnswer overAnswer)
        {
            if (canned) throw new CancelledException ();

            // if the user said it was ok to overwrite this one file before, it is still ok.
            // this happens during a resume after pause and we are restarting the copy/move.
            if (newFile.getAbsolutePath ().equals (pausedDuringOverwrite)) {
                overAnswer.overAnswer (AsyncFileTasks.OA_ONE);
                return;
            }

            // set up list of choices
            String[] choices = new String[4];
            choices[AsyncFileTasks.OA_ONE]  = "Yes, but just for this one file";
            choices[AsyncFileTasks.OA_ALL]  = "Yes, and overwrite any others";
            choices[AsyncFileTasks.OA_SKIP] = "No, but go on to next file";
            choices[AsyncFileTasks.OA_STOP] = "No, and stop processing";

            // set up listener to notify copy/move thread of the user's choice
            DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener () {
                @Override  // OnClickListener
                public void onClick (DialogInterface dialog, int which)
                {
                    adiag = null;
                    pausedDuringOverwrite = (which == AsyncFileTasks.OA_ONE) ? newFile.getAbsolutePath () : null;
                    overAnswer.overAnswer (which);
                }
            };

            // display alert dialog
            AlertDialog.Builder ab = new AlertDialog.Builder (ctx);
            ab.setTitle ("Overwrite " + newFile.getAbsolutePath ());
            ab.setItems (choices, listener);
            ab.setOnCancelListener (new DialogInterface.OnCancelListener () {
                @Override
                public void onCancel (DialogInterface dialogInterface)
                {
                    overAnswer.overAnswer (AsyncFileTasks.OA_STOP);
                }
            });
            adiag = ab.show ();
        }

        /**
         * The thread got an exception copying a file and is waiting to see if the user
         * wants to continue on with the next file or stop the copy altogether.
         * @param oldFile = file being copied from
         * @param newFile = file attempting to copy to
         * @param e = exception during copy
         * @param overAnswer = callback with user's response
         */
        @Override
        public void exception (IFile oldFile, IFile newFile, final Exception e, final AsyncFileTasks.IOverAnswer overAnswer)
        {
            // user has clicked cancel so don't bother with any of this
            if (canned) {
                overAnswer.excpAnswer (new CancelledException ());
                return;
            }

            // if we got here earlier on an inner level, just
            // pass the abort up through the levels cuz the
            // user has already told us to abort the copy
            if (copyExceptionAborted) {
                overAnswer.excpAnswer (e);
                return;
            }

            // display alert dialog and do callback when user responds
            AlertDialog.Builder ab = new AlertDialog.Builder (ctx);
            ab.setTitle ("Error copying " + oldFile.getAbsolutePath ());
            ab.setMessage (SshClient.GetExMsg (e));
            ab.setPositiveButton ("Continue with next file", new DialogInterface.OnClickListener () {
                @Override
                public void onClick (DialogInterface dialogInterface, int i)
                {
                    adiag = null;
                    overAnswer.excpAnswer (null);
                }
            });
            ab.setNegativeButton ("Cancel the copy", new DialogInterface.OnClickListener () {
                @Override
                public void onClick (DialogInterface dialogInterface, int i)
                {
                    adiag = null;
                    copyExceptionAborted = true;
                    overAnswer.excpAnswer (e);
                }
            });
            ab.setOnCancelListener (new DialogInterface.OnCancelListener () {
                @Override
                public void onCancel (DialogInterface dialogInterface)
                {
                    adiag = null;
                    copyExceptionAborted = true;
                    overAnswer.excpAnswer (e);
                }
            });
            adiag = ab.show ();
        }

        /**
         * The copy/move has completed for all selections, good or bad.
         * Take down any left over alert dialog then finish closing up.
         */
        @Override  // CopyMoveDelCallbacks
        public void completed (Exception e)
        {
            if (adiag != null) {
                adiag.dismiss ();
                adiag = null;
            }

            // maybe user has already seen the error message,
            // so don't bother them with it again
            if (copyExceptionAborted) e = null;

            // post completion
            super.completed (e);
        }
    }


    /**
     * Start deleting a list of files.
     * @param selecteds = files to be deleted
     * @param xfrProg = PROG_ progress dialog style flags
     */
    public static CopyMoveDelTask deleteFiles (
            Collection<? extends AsyncFileTasks.Selected> selecteds,
            int xfrProg)
    {
        DeleteCallbacks callbacks = new DeleteCallbacks ();
        callbacks.opcode  = "delete";
        callbacks.xfrProg = xfrProg & ~PROG_PRESCAN;
        callbacks.setSelecteds (selecteds);
        callbacks.startit ();
        return callbacks;
    }

    /**
     * GUI functions specific to delete.
     */
    private static class DeleteCallbacks extends CopyMoveDelCallbacks {

        /**
         * Start deleting the selected files.
         */
        @Override  // CopyMoveDelCallbacks
        protected AsyncFileTasks.CopyMoveDelFilesThread startup ()
        {
            return AsyncFileTasks.deleteFiles (selecteds, this);
        }

        /**
         * About to start deleting a selection of files.
         * Set the dialog title to indicate which selection is being deleted.
         * The message is used to identify which file of that selection is being done.
         */
        @Override  // CopyMoveDelTask
        public void selectedStart (AsyncFileTasks.Selected selected)
        {
            super.selectedStart (selected);
            setTitle (selected.file.getAbsolutePath ());
        }
    }


    /**
     * GUI functions common to copy, move, delete.
     */
    private static abstract class CopyMoveDelCallbacks implements AsyncFileTasks.ICopyMoveDelCB, CopyMoveDelTask {

        public int xfrProg;    // PROG_ progress display style flags
        public String opcode;  // "copy", "move", "delete"

        protected ArrayList<AsyncFileTasks.Selected> selecteds; // files to be copyied/moved/deleted
        protected boolean canned;                               // set when cancelled by user
        protected Context ctx;                                  // GUI context

        private AsyncFileTasks.CopyMoveDelFilesThread cmdft;
        private boolean hierProg;           // hierarchical progress display
        private boolean pawsed;             // set when paused
        private IFinished finished;         // call this when finished
        private LinearLayout progressView;  // display progress & buttons
        private Prog prog;                  // current file's progress
        private String titleStr = "";       // contents of pdiagTitle
        private TextView pdiagTitle;        // title for progress view

        /**
         * Copy the given list stripping out any possible GUI references.
         */
        public void setSelecteds (Collection<? extends AsyncFileTasks.Selected> sels)
        {
            selecteds = new ArrayList<AsyncFileTasks.Selected> (sels.size());
            int i = 0;
            for (AsyncFileTasks.Selected sel : sels) {
                AsyncFileTasks.Selected selcopy = new AsyncFileTasks.Selected ();
                selcopy.file   = sel.file;
                selcopy.outmap = sel.outmap;
                selecteds.add (i ++, selcopy);
            }
        }

        /**
         * Tell AsyncFileTasks to start copying/moving/deleting the selected files.
         */
        protected abstract AsyncFileTasks.CopyMoveDelFilesThread startup ();
        public void startit ()
        {
            hierProg = (xfrProg & PROG_HIERARC) != 0;
            cmdft = startup ();
        }

        /**
         * Create a GUI element to get progress.
         * @param ctx = GUI to create the element for
         * @param fini = function to call back when finished
         * @return GUI element that displays progress
         */
        @Override  // CopyMoveDelTask
        public View createGUI (Context ctx, IFinished fini)
        {
            this.ctx = ctx;
            this.finished = fini;

            final Button pdiagCanBut  = new Button (ctx);
            pdiagCanBut.setText ("Cancel");
            pdiagCanBut.setTextSize (SshClient.UNIFORM_TEXT_SIZE);
            pdiagCanBut.setOnClickListener (new View.OnClickListener () {
                @Override
                public void onClick (View view) {
                    AlertDialog.Builder ab = new AlertDialog.Builder (CopyMoveDelCallbacks.this.ctx);
                    ab.setTitle ("Cancel " + opcode);
                    ab.setMessage ("Are you sure?");
                    ab.setPositiveButton ("YES! CANCEL!", new DialogInterface.OnClickListener () {
                        @Override
                        public void onClick (DialogInterface dialogInterface, int i)
                        {
                            abortabort ();
                        }
                    });
                    String opcoding = (opcode + "ing").replace ("eing", "ing");
                    ab.setNegativeButton (pawsed ? "No, stay paused" : "No, keep " + opcoding, null);
                    ab.show ();
                }
            });

            final Button pdiagPawsBut = new Button (ctx);
            pdiagPawsBut.setText (pawsed ? "Resume" : "Pause");
            pdiagPawsBut.setTextSize (SshClient.UNIFORM_TEXT_SIZE);
            pdiagPawsBut.setOnClickListener (new View.OnClickListener () {
                @Override
                public void onClick (View view) {
                    if (pdiagPawsBut.getText ().toString ().equals ("Pause")) {

                        // user wants to pause the transfer, tell thread to stop copying.
                        pawsed = true;
                        cmdft.setPaused (true);
                        pdiagPawsBut.setText ("Resume");
                    } else {

                        // user wants to resume the transfer, tell thread to resume copy.
                        pawsed = false;
                        cmdft.setPaused (false);
                        pdiagPawsBut.setText ("Pause");
                    }

                    // maybe our caller wants to know about being paused or not
                    if (finished != null) finished.paused (pawsed);
                }
            });

            String opcoding = opcode + "ing";
            opcoding = opcoding.replace ("eing", "ing");
            opcoding = " " + opcoding.substring (0, 1).toUpperCase () + opcoding.substring (1);
            TextView opcodingView = new TextView (ctx);
            opcodingView.setTextSize (SshClient.UNIFORM_TEXT_SIZE * 1.250F);
            opcodingView.setText (opcoding);

            pdiagTitle = new TextView (ctx);
            pdiagTitle.setTextSize  (SshClient.UNIFORM_TEXT_SIZE * 1.125F);
            pdiagTitle.setText (titleStr);

            /*
                Cancel button  Pause button  Opcoding
                TITLE................................
                MESSAGE.............................. \
                GraphicProgress......................  repeat for each level
                TextProgress......................... /
             */

            LinearLayout llh = new LinearLayout (ctx);
            llh.setOrientation (LinearLayout.HORIZONTAL);
            llh.addView (pdiagCanBut);
            llh.addView (pdiagPawsBut);
            llh.addView (opcodingView);

            progressView = new LinearLayout (ctx);
            progressView.setOrientation (LinearLayout.VERTICAL);
            progressView.addView (llh);
            progressView.addView (pdiagTitle);

            // fill in current values if any
            createStackedProgGUI (prog);
            if (prog != null) prog.update (0);

            // start getting callbacks to update the GUI elements
            cmdft.setCallbacks (this);

            // tell caller what to display
            return progressView;
        }

        private void createStackedProgGUI (Prog p)
        {
            if (p != null) {
                createStackedProgGUI (p.outer);
                p.createGUI ();
            }
        }

        /**
         * Delete the GUI element that gets progress updates.
         */
        @Override  // CopyMoveDelTask
        public void deleteGUI ()
        {
            // make sure we won't get any more callbacks to update GUI elements
            cmdft.setCallbacks (null);

            // clean out all progress bar GUIs
            for (Prog p = prog; p != null; p = p.outer) p.deleteGUI ();

            // forget about all pointers to GUI stuff so it will get garbage collected
            this.ctx      = null;
            this.finished = null;
            pdiagTitle    = null;
            progressView  = null;
        }

        /**
         * Retrieve current GUI, if any.
         */
        @Override  // CopyMoveDelTask
        public View currentGUI ()
        {
            return progressView;
        }

        /**
         * Abort transfer.
         */
        @Override  // CopyMoveDelTask
        public void abortabort ()
        {
            // tell thread to throw a CancelledException sometime soon
            canned = true;

            // if paused, resume thread so it will see cancel flag and abort
            if (pawsed) {
                pawsed = false;
                cmdft.setPaused (false);
            }
        }

        /**
         * Subclasses use this to set title string to be displayed if/when GUI is attached.
         */
        protected void setTitle (String title)
        {
            titleStr = title;
            if (pdiagTitle != null) pdiagTitle.setText (title);
        }

        /**
         * About to start copying/moving/deleting a selection of files.
         */
        @Override  // AsyncFileTasks.ICopyMoveDelCB
        public void selectedStart (AsyncFileTasks.Selected selected)
        {
            if (canned) throw new CancelledException ();
        }

        /**
         * Finished processing a selection.
         */
        @Override  // AsyncFileTasks.ICopyMoveDelCB
        public void selectedDone ()
        { }

        /**
         * Must be overridden to provide overwrite & exception dialogs.
         */
        @Override  // AsyncFileTasks.ICopyMoveDelCB
        public void overwrite (IFile newFile, AsyncFileTasks.IOverAnswer overAnswer)
        {
            throw new RuntimeException ("overwrite called in " + opcode);
        }
        @Override  // AsyncFileTasks.ICopyMoveDelCB
        public void exception (IFile oldFile, IFile newFile, final Exception e, final AsyncFileTasks.IOverAnswer overAnswer)
        {
            overAnswer.excpAnswer (e);
        }

        /**
         * The whole copy/move/delete function has completed, good or bad.
         */
        @Override  // AsyncFileTasks.ICopyMoveDelCB
        public void completed (final Exception e)
        {
            if ((e != null) && !(e instanceof CancelledException)) {

                // some weird exception, show error dialog then call finished()
                Log.d (TAG, opcode + " exception", e);
                AlertDialog.Builder ab = new AlertDialog.Builder (ctx);
                ab.setTitle ("Error during " + opcode);
                ab.setMessage (SshClient.GetExMsg (e));
                final DialogInterface.OnClickListener ablis = new DialogInterface.OnClickListener () {
                    @Override
                    public void onClick (DialogInterface dialog, int which)
                    {
                        IFinished f = finished;
                        if (f != null) {
                            finished = null;
                            f.finished (e);
                        }
                    }
                };
                ab.setPositiveButton ("OK", ablis);
                ab.setOnCancelListener (new DialogInterface.OnCancelListener () {
                    @Override
                    public void onCancel (DialogInterface dialogInterface)
                    {
                        ablis.onClick (dialogInterface, 0);
                    }
                });
                ab.show ();
            } else if (finished != null) {

                // normal or cancelled completion, call finished()
                IFinished f = finished;
                finished = null;
                f.finished (e);
            }
        }

        /**
         * About to start copying/moving/deleting a particular file.
         * Set the message in the progress dialog to indicate which file.
         * For delete, we only get these when deleting a directory and
         * it indicates progress for deleting the files within a directory.
         */
        @Override  // AsyncFileTasks.ICopyMoveDelCB
        public void startFile (IFile oldFile, IFile newFile, long bytes)
        {
            if (canned) throw new CancelledException ();

            if (prog != null) prog.setOnTop (false);

            boolean isdir;
            try { isdir = oldFile.isDirectory (); } catch (IOException ioe) { isdir = false; }

            prog = new Prog (
                    prog,
                    oldFile.getAbsolutePath (),
                    bytes,
                    (newFile == null) && !opcode.equals ("delete"),
                    isdir
            );
            prog.createGUI ();
        }

        /**
         * There is an update as to how much of the file has been processed so far.
         */
        @Override  // AsyncFileTasks.ICopyMoveDelCB
        public void partialCopy (long bytes)
        {
            prog.sofarBytes = bytes;
            if (prog.nsamples == 0) {
                prog.startedAt = SystemClock.uptimeMillis ();
                prog.startOffs = bytes;
            }
            prog.nsamples ++;
            prog.update (0);

            if (canned) throw new CancelledException ();
        }

        /**
         * Have reached the end of processing for a particular file.
         */
        @Override  // AsyncFileTasks.ICopyMoveDelCB
        public void endOfFile ()
        {
            prog.deleteGUI ();
            prog = prog.outer;
            if (prog != null) prog.setOnTop (true);
        }

        /**
         * Holds progress values for a level of a transfer.
         */
        private class Prog implements SeekBar.OnSeekBarChangeListener {
            private final static int PDIAGBARMAX = 1024;

            public Prog outer;

            public int  nsamples;             // number of progress samples received so far
            public long sofarBytes;           // bytes copied so far
            public long startedAt;            // uptimeMillis() of first sample
            public long startOffs;            // byte offset of first sample

            private boolean preScan;
            private boolean isDir;
            private int    lastBarSet;        // last value set in bar
            private long   totalBytes;        // file size in bytes
            private String message;           // what goes in message box
            private String totalBytesString;  // file size in bytes
            private StringBuilder buf;        // used to build pdiagSoFar string

            private boolean onDisplay;        // currently part of progressView
            private ProgressBar pdiagBar;     // graphical progress presentation
            private TextView pdiagMsgBox;     // filename being operated on
            private TextView pdiagSoFar;      // bytes copied so far

            public Prog (Prog outr, String msg, long total, boolean ps, boolean dir)
            {
                outer      = outr;
                message    = msg;
                totalBytes = total;
                preScan    = ps;
                isDir      = dir;
                startedAt  = SystemClock.uptimeMillis ();

                buf = new StringBuilder (80);
                FileExplorerNav.fileSizeString (buf, totalBytes);
                totalBytesString = buf.toString ();
            }

            /**
             * Create GUI elements.  Make them visible if appropriate.
             */
            public void createGUI ()
            {
                if ((message != null) && (progressView != null)) {
                    pdiagMsgBox = new TextView (ctx);
                    pdiagMsgBox.setTextSize (SshClient.UNIFORM_TEXT_SIZE);

                    String msg = message;
                    if (hierProg && (outer != null) && (outer.message != null) && (outer.message.length () > 3)
                            && msg.startsWith (outer.message)) {
                        msg = "..." + msg.substring (outer.message.length ());
                    }
                    pdiagMsgBox.setText (msg);

                    if (!preScan) {
                        pdiagSoFar = new TextView (ctx);
                        pdiagSoFar.setTextSize (SshClient.UNIFORM_TEXT_SIZE);
                        pdiagSoFar.setTypeface (Typeface.MONOSPACE);

                        SeekBar sb = new SeekBar (ctx);
                        sb.setOnSeekBarChangeListener (this);
                        sb.setThumb (null);
                        pdiagBar = sb;

                        pdiagBar.setLayoutParams (new LinearLayout.LayoutParams (LinearLayout.LayoutParams.FILL_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0F));
                        pdiagBar.setMax (PDIAGBARMAX);
                    }

                    // if hierarchical display, all elements are always visible
                    // otherwise, they're visible only if they're on top of stack
                    onDisplay = hierProg || (this == prog);
                    if (onDisplay) {
                        progressView.addView (pdiagMsgBox);
                        if (pdiagBar   != null) progressView.addView (pdiagBar);
                        if (pdiagSoFar != null) progressView.addView (pdiagSoFar);
                    }
                }
            }

            /**
             * Remove all references to GUI elements, visible or not.
             */
            public void deleteGUI ()
            {
                if (pdiagMsgBox != null) progressView.removeView (pdiagMsgBox);
                if (pdiagBar    != null) progressView.removeView (pdiagBar);
                if (pdiagSoFar  != null) progressView.removeView (pdiagSoFar);

                pdiagMsgBox = null;
                pdiagBar    = null;
                pdiagSoFar  = null;
                onDisplay   = false;
            }

            /**
             * This element has transitioned to/from being on the top.
             * Maybe change its visibility.
             */
            public void setOnTop (boolean onTop)
            {
                // if GUI detached, don't bother updating anything
                // if hierProg, all elements are always visible so we just leave it alone
                // otherwise, make a change to display only if we are changing
                if ((pdiagMsgBox != null) && !hierProg && (onDisplay ^ onTop)) {
                    onDisplay = onTop;
                    if (onDisplay) {
                        update (0);
                        progressView.addView (pdiagMsgBox);
                        if (pdiagBar    != null) progressView.addView (pdiagBar);
                        if (pdiagSoFar  != null) progressView.addView (pdiagSoFar);
                    } else {
                        progressView.removeView (pdiagMsgBox);
                        if (pdiagBar    != null) progressView.removeView (pdiagBar);
                        if (pdiagSoFar  != null) progressView.removeView (pdiagSoFar);
                    }
                }
            }

            /**
             * Update GUI elements, if they are visible, to reflect current progress values.
             * @param augment = this many total bytes have been transferred by a sub-file/directory
             *                  so far and haven't been accounted for yet in this directory's sofarBytes
             */
            public void update (long augment)
            {
                // 1. augment is non-zero only if this is a directory, giving number of bytes
                //    processed in the currently open sub-file so far
                // 2. the total size of all the files in this directory is calculated only if PROG_PRESCAN is set
                // 3. thus the augment is useless if PROG_PRESCAN is clear and should be ignored
                if ((xfrProg & PROG_PRESCAN) == 0) augment = 0;

                // so now this is the total bytes processed in this file/directory as of this moment,
                // taking into account all currently open sub-files
                augment += sofarBytes;

                if (onDisplay && (totalBytes > 0)) {

                    // if we are showing the graphical progress bar, update it
                    // it is simply the ration of number of bytes processed as of this
                    // moment to the total number of bytes to be processed
                    if (pdiagBar != null) {
                        lastBarSet = (int)(augment * PDIAGBARMAX / totalBytes);
                        pdiagBar.setProgress (lastBarSet);
                    }

                    // if we are showing the textual progress, update it
                    if (pdiagSoFar != null) {
                        buf.delete (0, buf.length ());

                        // textual ratio of the actual numbers and as a percentage
                        FileExplorerNav.fileSizeString (buf, augment);
                        int len = totalBytesString.length ();
                        while (buf.length () < len) buf.insert (0, ' ');
                        buf.append ('/');
                        buf.append (totalBytesString);

                        Formatter fmt = new Formatter (buf, null);
                        int percent = (int)(augment * 100 / totalBytes);
                        fmt.format ("%4d%%", percent);

                        // estimate time remaining for this file/directory
                        // do it only if it is a regular file or a pre-scanned directory
                        // we don't have the actual number of bytes for a non-pre-scanned directory
                        if (!isDir || ((xfrProg & PROG_PRESCAN) != 0)) {
                            long sfb = augment - startOffs;
                            if (((percent > 0) || (sfb > 65535)) && (sfb > 0)) {
                                long now = SystemClock.uptimeMillis ();
                                long sofarTime = now - startedAt;
                                if (sofarTime >= 1000) {
                                    long tot       = totalBytes - startOffs;
                                    long totalTime = (long)((float)sofarTime / (float)sfb * (float)tot);

                                    int  secsLeft  = (int)((totalTime - sofarTime + 999) / 1000);
                                    int  ss = secsLeft % 60;
                                    int  mm = (secsLeft / 60) % 60;
                                    int  hh = secsLeft / 3600;
                                    fmt.format ("  %02d:%02d:%02d", hh, mm, ss);

                                    Time doneAt = new Time (Time.getCurrentTimezone ());
                                    doneAt.set (System.currentTimeMillis () + totalTime - sofarTime);
                                    fmt.format ("/%02d:%02d:%02d", doneAt.hour, doneAt.minute, doneAt.second);

                                    int  kps  = (int)(augment / sofarTime);
                                    if (kps > 0) fmt.format ("  %dK/s", kps);
                                }
                            }
                        }
                        pdiagSoFar.setText (buf);
                    }

                    // update outer levels too to count their timers down
                    // we pass augment cuz it contains how many bytes we have processed
                    // including any of our currently open children, as of this moment
                    if (outer != null) outer.update (augment);
                }
            }

            // SeekBar.OnSeekBarChangeListener
            // ...these just keep the user from moving slider
            //    by positioning it right back where it was
            @Override
            public void onProgressChanged (SeekBar seekBar, int i, boolean b) {
                seekBar.setProgress (lastBarSet);
            }
            @Override
            public void onStartTrackingTouch (SeekBar seekBar) {
                seekBar.setProgress (lastBarSet);
            }
            @Override
            public void onStopTrackingTouch (SeekBar seekBar) {
                seekBar.setProgress (lastBarSet);
            }
        }
    }

    private static class CancelledException extends RuntimeException {
        public CancelledException () { super ("cancelled"); }
    }
}
