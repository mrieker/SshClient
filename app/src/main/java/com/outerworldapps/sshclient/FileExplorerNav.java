/**
 * Combined with FileExplorerView, this view navigates around a single domain.
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
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Stack;
import java.util.TreeMap;

@SuppressLint({ "SetTextI18n", "ViewConstructor" })
public class FileExplorerNav extends LinearLayout {
    public final static String TAG = "SshClient";

    private FileExplorerView explorerView;
    private FilesTextView filesTextView;
    private FilesTextViewSV filesTextViewSV;
    private float txtsize;  // filesTextView text size
    private FilesTextZoom filesTextZoom;
    private HashSet<IFile> highlightedFiles;
    private IFile currentDir;
    private int bgcolor;    // filesTextView background color
    private int fgcolor;    // filesTextView foreground color
    private int hlcolor;    // filesTextView highlight color
    private LinearLayout dirButtonRowLL;
    private Paint hlpaint;
    private SshClient sshclient;
    private String domain;
    private TreeMap<String,IFile> knownReadables;
    private TextView domNameTV;
    private View.OnClickListener dirButtonListener;

    public IFile getCurrentDir () { return currentDir; }
    public String getDomain () { return domain; }

    public FileExplorerNav (SshClient sc, String dom)
    {
        super (sc);
        sshclient = sc;
        domain    = dom;

        bgcolor = Color.BLACK;
        fgcolor = Color.WHITE;
        hlcolor = Color.GRAY;

        hlpaint = new Paint ();
        hlpaint.setColor (hlcolor);
        hlpaint.setStyle (Paint.Style.FILL);

        highlightedFiles = new HashSet<> ();
        knownReadables = new TreeMap<> ();

        domNameTV = sshclient.MyTextView ();
        domNameTV.setText ("  " + dom + "  ");
        domNameTV.setTextSize (SshClient.UNIFORM_TEXT_SIZE * 1.5F);

        dirButtonListener = new View.OnClickListener () {
            public void onClick (View v)
            {
                if (!explorerView.hasXfersRunning (true)) {
                    setCurrentDir ((IFile)v.getTag ());
                }
            }
        };

        setOrientation (LinearLayout.VERTICAL);

        dirButtonRowLL = new LinearLayout (sshclient);
        dirButtonRowLL.setOrientation (LinearLayout.HORIZONTAL);
        HorizontalScrollView dirButtonRowSV = new HorizontalScrollView (sshclient);
        dirButtonRowSV.addView (dirButtonRowLL);
        addView (dirButtonRowSV);

        filesTextZoom   = new FilesTextZoom ();
        filesTextView   = new FilesTextView ();
        filesTextViewSV = new FilesTextViewSV ();
        filesTextViewSV.addView (filesTextView);
        addView (filesTextViewSV);
    }

    public void setFENColorsNSize (int bg, int fg, int hl, int ts)
    {
        if ((bgcolor != bg) || (fgcolor != fg) || (hlcolor != hl) || (txtsize != ts)) {
            bgcolor = bg;
            fgcolor = fg;
            hlcolor = hl;
            filesTextView.setTextSize (ts);
            filesTextView.setFENColors ();
        }
    }

    public void setFileExplorerView (FileExplorerView fev)
    {
        if (fev == null) throw new IllegalArgumentException ("FileExplorerView argument is null");
        if (fev.getContext () != sshclient) throw new IllegalArgumentException ("FileExplorerView context different");
        if (explorerView != null) throw new IllegalArgumentException ("already part of a FileExplorerView");
        explorerView = fev;
    }

    public FileExplorerView getFileExplorerView ()
    {
        return explorerView;
    }

    /**
     * Add all the directories the given sshclient should have access to
     * as known readables so the user should always be able to navigate
     * back to them, even if some intermediate directory isn't readable.
     * @param act = sshclient to scan
     */
    public void addReadables (Context act)
    {
        addReadable (act.getCacheDir ());
        addReadable (act.getExternalCacheDir ());
        addReadable (act.getFilesDir ());
        addReadable (act.getExternalFilesDir (null));

        addReadable (Environment.getDataDirectory ());
        addReadable (Environment.getDownloadCacheDirectory ());
        addReadable (Environment.getExternalStorageDirectory ());
        addReadable (Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_ALARMS));
        addReadable (Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_DCIM));
        addReadable (Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_DOWNLOADS));
        addReadable (Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_MOVIES));
        addReadable (Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_MUSIC));
        addReadable (Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_NOTIFICATIONS));
        addReadable (Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_PICTURES));
        addReadable (Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_PODCASTS));
        addReadable (Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_RINGTONES));
        addReadable (Environment.getRootDirectory ());
    }

    /**
     * Add a directory that is most likely known to be readable
     * to the list of known readable directories.
     *  input rd = some directory that is probably readable
     *  returns true: it (or one of its parents) was added
     *         false: nothing was added
     */
    public void addReadable (java.io.File rd)
    {
        addReadable (new FileIFile (rd));
    }
    public boolean addReadable (IFile rd)
    {
        // make sure we are given a readable directory
        try {
            if (!rd.exists () || !rd.isDirectory () || !rd.canRead ()) {
                return false;
            }
        } catch (IOException ioe) {
            return false;
        }

        // if the direct parent is readable, add that instead
        // as the user will be able to navigate back to the
        // given directory from the parent
        IFile parent = rd.getParentFile ();
        if ((parent == null) || !addReadable (parent)) {

            // can't read direct parent, so the user can't navigate
            // from the parent to this directory, so add this directory
            // to the list of known readables.  using a tree keying
            // off absolute path will discard duplicates.
            String path = rd.getAPWithSlashNX ();
            knownReadables.put (path, rd);
        }
        return true;
    }

    /*******************\
     *  IFile listing  *
    \*******************/

    /**
     * See if the given file is listed on the display.
     */
    public boolean isFileListed (IFile whatFile)
    {
        return filesTextView.isFileListed (whatFile);
    }

    /**
     * Clear out all highlighted file markers.
     */
    public void clearHighlightedFiles ()
    {
        for (Iterator<IFile> it = highlightedFiles.iterator (); it.hasNext ();) {
            IFile file = it.next ();
            it.remove ();
            filesTextView.checkHighlight (file);
        }
    }

    /**
     * Set the given file to be highlighted or not in the listings
     */
    public void setFileHighlighted (IFile whatFile, boolean highlight)
    {
        if (highlight) {
            highlightedFiles.add (whatFile);
        } else {
            highlightedFiles.remove (whatFile);
        }
        filesTextView.checkHighlight (whatFile);
    }

    /**
     * Set directory that we are displaying in the view.
     * Reload if same as current directory.
     * @param cd = directory to display (null just to reload)
     */
    public void setCurrentDir (final IFile cd)
    {
        // if transfers in progress, queue it to run later so as to not jam up ssh connection
        explorerView.runWhenNoXfers (new Runnable () {
            public void run ()
            {
                // nothing else going on, start scanning the directory
                if (cd != null) currentDir = cd;
                explorerView.savestate.put ("nav:currentDir:" + domain, currentDir);

                // don't actually scan until we are made current, if not already,
                // simply to avoid unnecessary scanning especially during startup
                if (explorerView.getCurrentFileNavigator () == FileExplorerNav.this) {
                    explorerView.incXfersRunning (1);
                    DirectoryScanner ds = new DirectoryScanner ();
                    ds.execute ();
                }
            }
        });
    }

    private class DirectoryScanner extends AsyncTask<Void,Void,Exception> {
        private ProgressDialog pdiag;

        @Override
        protected void onPreExecute ()
        {
            // blank out anything that happens to be there while scanning
            // so user doesn't have anything to click on while scanning
            dirButtonRowLL.removeAllViews ();
            filesTextView.clearView ();

            // show progress dialog while scanning
            pdiag = new ProgressDialog (sshclient);
            pdiag.setIndeterminate (true);
            pdiag.setTitle ("Scanning " + currentDir.getAbsolutePath ());
            pdiag.show ();
        }

        @Override
        protected Exception doInBackground (Void[] params)
        {
            try {
                // read the directory contents
                IFile[] array = currentDir.listFiles ();

                // sort names in this directory
                FileUtils.sortDirectory (array);

                // format as much as we can while we're in a thread
                String cdPath = currentDir.getAPWithSlash ();
                filesTextView.formatDirContents (array, cdPath);

                return null;
            } catch (IOException ioe) {
                Log.d (TAG, "error scanning directory " + currentDir.getAbsolutePath (), ioe);
                return ioe;
            }
        }

        @Override
        protected void onPostExecute (Exception e)
        {
            pdiag.dismiss ();
            if (e != null) {
                sshclient.ErrorAlert ("Error scanning " + currentDir.getAbsolutePath (), SshClient.GetExMsg (e));
            }
            directoryScanComplete (null);
            explorerView.incXfersRunning (-1);
        }
    }

    /**
     * Start searching the current directory tree for files that match the given wildcard.
     * Display them in the directory's text view just like normal directory contents.
     * @param wildcard  = wildcards in that directory tree to search for
     */
    public void initiateTreeSearch (IFile directory, String wildcard, boolean caseSens)
    {
        // we can only do one of these at a time because filesTextView.formatDirContents()
        // uses instance variables within filesTextView.  and really we only have one screen
        // to display results on anyway.
        if (!explorerView.hasXfersRunning (true)) {
            explorerView.incXfersRunning (1);

            // we will display everything relative to the given directory
            currentDir = directory;
            explorerView.savestate.put ("nav:currentDir:" + domain, currentDir);

            // start searching
            SearchAsyncTask sat = new SearchAsyncTask ();
            sat.wildcard = wildcard;
            sat.caseSens = caseSens;
            sat.execute ();
        }
    }

    private class SearchAsyncTask extends AsyncTask<Void,Object,Exception> {
        public boolean caseSens;
        public String wildcard;
        public volatile boolean canned;

        private int numFound;
        private LinkedList<IFile> foundFiles;
        private ProgressDialog pdiag;
        private String searchroot;

        @Override
        protected void onPreExecute ()
        {
            pdiag = new ProgressDialog (sshclient);
            pdiag.setIndeterminate (true);
            searchroot = currentDir.getAPWithSlashNX ();
            pdiag.setTitle ("Searching " + searchroot + " for " + wildcard);
            pdiag.setButton ("Cancel", new DialogInterface.OnClickListener () {
                @Override
                public void onClick (DialogInterface dialogInterface, int i)
                {
                    canned = true;
                }
            });
            pdiag.show ();
        }

        @Override
        protected Exception doInBackground (Void[] params)
        {
            try {
                // convert wildcard to lower case if case-insensitive search
                if (!caseSens) wildcard = wildcard.toLowerCase ();

                // use a linked list because we have no idea how many matches we will get
                foundFiles = new LinkedList<> ();

                // search starting with the current directory
                searchTree (currentDir);

                // make an array that formatDirContents() can process
                // no need to sort because we sorted the directories as we read them
                IFile[] array = new IFile[foundFiles.size()];
                array = foundFiles.toArray (array);

                // format the screen listing contents
                filesTextView.formatDirContents (array, currentDir.getAPWithSlash ());

                // successful
                return null;
            } catch (IOException ioe) {
                return ioe;
            }
        }

        /**
         * Search the given directory for files that match the wildcard.
         * Add the matches to foundFiles.
         */
        private void searchTree (IFile dir) throws IOException
        {
            IFile[] files = dir.listFiles ();
            int len = files.length;
            if (len > 0) {
                String curSearchDir = dir.getAPWithSlash ();
                publishProgress (curSearchDir, numFound);
                FileUtils.sortDirectory (files);
                for (int i = 0; i < len && !canned; i ++) {
                    IFile file = files[i];
                    String name = file.getName ();
                    if (!caseSens) name = name.toLowerCase ();
                    if (FileUtils.wildcardMatch (wildcard, name, 0, 0)) {
                        foundFiles.addLast (file);
                        numFound ++;
                        publishProgress (curSearchDir, numFound);
                    }
                    if (file.isDirectory ()) {
                        // if directory is readable, scan it
                        if (file.canRead ()) {
                            searchTree (file);
                        } else {
                            // not readable, scan any known readable directories under it
                            String unreadableDirName = file.getAPWithSlash ();
                            for (String absPath : knownReadables.keySet ()) {
                                if (absPath.startsWith (unreadableDirName)) {
                                    searchTree (knownReadables.get (absPath));
                                }
                            }
                        }
                    }
                }
            }
        }

        @Override
        protected void onProgressUpdate (Object[] params)
        {
            String curSearchDir = (String)  params[0];
            int    numFoundSync = (Integer) params[1];
            if (curSearchDir.startsWith (searchroot)) {
                curSearchDir = curSearchDir.substring (searchroot.length ());
            }
            pdiag.setMessage (curSearchDir + "\n" + numFoundSync + " found so far");
        }

        @Override
        protected void onPostExecute (Exception e)
        {
            pdiag.dismiss ();
            if (e != null) {
                sshclient.ErrorAlert ("Search error", SshClient.GetExMsg (e));
            }
            directoryScanComplete (" " + wildcard + " search results");
            explorerView.incXfersRunning (-1);
        }
    }

    /**
     * Ready to display the prebuilt directory listing contents.
     *   currentDir = directory we just built the contents of
     *   filesTextView.formatDirContents () has been called to build the contents
     *     and has filled in filesTextView.dirContents.
     */
    private void directoryScanComplete (String notation)
    {
        // generate row of buttons that user can click that look at parent directories
        // include a button for this directory at the end so they can refresh
        dirButtonRowLL.removeAllViews ();
        dirButtonRowLL.addView (domNameTV);
        Stack<IFile> parentStack = new Stack<> ();
        parentStack.push (null);
        IFile parentdir;
        for (parentdir = currentDir; parentdir != null; parentdir = parentdir.getParentFile ()) {
            parentStack.push (parentdir);
        }
        String shownSoFar = "";
        while ((parentdir = parentStack.pop ()) != null) {
            boolean readable = false;
            boolean writable = false;
            try { readable = parentdir.canRead  (); } catch (IOException ioe) { Log.d (TAG, "canRead() error " + parentdir.getAbsolutePath (), ioe); }
            try { writable = parentdir.canWrite (); } catch (IOException ioe) { Log.d (TAG, "canWrite() error " + parentdir.getAbsolutePath (), ioe); }

            // if this directory is known to be readable, remember it so user can navigate back
            if (readable) {
                addReadable (currentDir);
            }

            // see if the directory is readable.  if so, make a button for it.
            // always make a button for current dir, grayed out if it isn't
            // readable, eg, could have been deleted by something else.
            if (readable || (parentdir == currentDir)) {

                // make a button for it
                String parentPath = parentdir.getAPWithSlashNX ();
                String butStr = parentPath;
                if (butStr.startsWith (shownSoFar)) {
                    butStr = butStr.substring (shownSoFar.length ());
                }
                shownSoFar = parentPath;

                Button but = sshclient.MyButton ();
                but.setOnClickListener (dirButtonListener);
                but.setTag (parentdir);
                but.setText (butStr);
                but.setTextColor (!readable ? Color.GRAY : !writable ? Color.BLACK : Color.RED);
                dirButtonRowLL.addView (but);
            }
        }

        // maybe include a notation along with the buttons
        // indicating the conditions of the listing
        if (notation != null) {
            TextView ntv = sshclient.MyTextView ();
            ntv.setText (notation);
            dirButtonRowLL.addView (ntv);
        }

        // set up function button row
        if (explorerView != null) {
            explorerView.setFuncButtonRow ();
        }

        // display list of files
        filesTextView.displayDirContents ();
    }

    /**
     * Horizontal scroller for FilesTextView.
     * Detects single-clicks to select/deselect files.
     * Intercepts zooming to change text size.
     */
    private class FilesTextViewSV extends HorizontalScrollView {
        private boolean gotDown;
        private float downX;

        public FilesTextViewSV ()
        {
            super (sshclient);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent (@NonNull MotionEvent me)
        {
            filesTextZoom.OnTouchEvent (me);

            /*
             * Process clicking on a file to select/deselect it.
             * Ideally this should be processed in FileTextView
             * but it seems to only get MotionEvent.ACTION_DOWN.
             */
            switch (me.getAction () & MotionEvent.ACTION_MASK) {

                // save the X value when clicked on
                case MotionEvent.ACTION_DOWN: {
                    gotDown = true;
                    downX = me.getX ();
                    break;
                }

                // the vertical scroller is telling us the the down event
                // is being used for scrolling, so don't use it to select
                case MotionEvent.ACTION_CANCEL: {
                    gotDown = false;
                    break;
                }

                // block selecting the file if there is significant left/right motion
                // we theoretically don't get Y motion because we are inside a vertical scroller
                case MotionEvent.ACTION_MOVE: {
                    if (gotDown && (Math.abs (me.getX () - downX) * 2.0F > filesTextView.getLineHeight ())) {
                        gotDown = false;
                    }
                    break;
                }

                // we got an UP event.
                // if it was not cancelled by vertical or horizontal scrolling,
                // use it to select/deselect the corresponding file.
                case MotionEvent.ACTION_UP: {
                    if (gotDown) {
                        gotDown = false;
                        filesTextView.motionEventUp (me);
                    }
                    break;
                }
            }

            return super.onTouchEvent (me);
        }
    }

    /**
     * View that holds the list of files, one per line.
     */
    private class FilesTextView extends View {
        private static final String datespec = "  yyyy-MM-dd HH:mm:ss  ";
        private static final String datespac = "                       ";
        private SimpleDateFormat datefmt = new SimpleDateFormat (datespec, Locale.US);
        private StringBuffer datestr = new StringBuffer ();

        private final Object dcLock = new Object ();

        private ArrayList<ViewedFile> dirContents;
        private float charWidth;
        private int lastOneSeld = -1;
        private int lastTwoSeld = -2;
        private int lineHeight;
        private int sizeWidth;   // width of file size field in chars
        private int widestLine;  // widest line in characters
        private long lastSelTime;
        private Paint boxpaint;
        private Paint textpaint;
        private Rect bounds = new Rect ();
        private String dirNameAPWS;
        private String spaces = " ";

        public FilesTextView ()
        {
            super (sshclient);
            dirContents = new ArrayList<> (1);
            boxpaint = new Paint ();
            boxpaint.setStyle (Paint.Style.STROKE);
            textpaint = new Paint ();
            textpaint.setStyle (Paint.Style.FILL);
            textpaint.setTypeface (Typeface.MONOSPACE);
            setTextSize (sshclient.getSettings ().font_size.GetValue ());
        }

        /**
         * The colors have been changed, go fix stuff up.
         */
        public void setFENColors ()
        {
            setBackgroundColor (bgcolor);
            invalidate ();
        }

        /**
         * Format the directory contents to be displayed in the view.
         * Runs in a sub-thread, so no GUI component access allowed.
         * @param array = sorted array files in the directory
         * @param container = absolute path of the directory including trailing "/"
         */
        public void formatDirContents (IFile[] array, String container)
        {
            dirNameAPWS = container;

            ArrayList<ViewedFile> dc = new ArrayList<> (array.length);

            boolean inclHidden = sshclient.getSettings ().incl_hid.GetValue ();
            long largestFile = 0;

            for (IFile aFile : array) {
                try {
                    if (inclHidden || !aFile.isHidden ()) {

                        // add the file's entry to the output
                        ViewedFile vf = new ViewedFile ();
                        vf.file = aFile;
                        dc.add (vf);
                        try {
                            long aFileLength = aFile.length ();
                            if (largestFile < aFileLength) largestFile = aFileLength;
                        } catch (IOException ioe) {
                            // eg, dead softlink
                        }

                        // see if it's an unreadable directory
                        if (aFile.isDirectory () && !aFile.canRead ()) {
                            String unreadableDirName = aFile.getAPWithSlash ();

                            // if so, see if we have any known readable directories under it
                            for (String absPath : knownReadables.keySet ()) {
                                if (absPath.startsWith (unreadableDirName)) {

                                    // for each found, list it as a separate entry the user can click on
                                    vf = new ViewedFile ();
                                    vf.file = knownReadables.get (absPath);
                                    dc.add (vf);
                                    if (largestFile < vf.file.length ()) largestFile = vf.file.length ();
                                }
                            }
                        }
                    }
                } catch (IOException ioe) {
                    Log.w (TAG, "dir scan error " + aFile.getAbsolutePath (), ioe);
                }
            }

            // compute width needed for file size field to accommodate largest number
            StringBuilder sb = new StringBuilder ();
            fileSizeString (sb, largestFile);
            int sizwid = sb.length ();

            // compute longest line length given that width for size field
            int widlin = 0;
            for (ViewedFile vf : dc) {
                int len = vf.getLine (sizwid).length ();
                if (widlin < len) widlin = len;
            }

            synchronized (dcLock) {
                dirContents = dc;
                sizeWidth   = sizwid;
                widestLine  = widlin;
                lastOneSeld = -1;
                lastTwoSeld = -2;
            }
        }

        /**
         * See if the given file is in the list of displayed files.
         */
        public boolean isFileListed (IFile whatFile)
        {
            synchronized (dcLock) {
                for (ViewedFile vf : dirContents) {
                    if (vf.file.equals (whatFile)) return true;
                }
            }
            return false;
        }

        /**
         * Clear everything out of display.
         */
        public void clearView ()
        {
            synchronized (dcLock) {
                dirContents.clear ();
            }
            requestLayout ();
            invalidate ();
        }

        /**
         * Now we are back in the GUI thread so we can display the directory contents.
         */
        public void displayDirContents ()
        {
            requestLayout ();
            invalidate ();
        }

        /**
         * Re-measure everything so scrollers know how big we need to be.
         */
        public void setTextSize (float ts)
        {
            txtsize = ts;

            // voodoo from TextView.java
            Resources r = sshclient.getResources ();
            ts = TypedValue.applyDimension (TypedValue.COMPLEX_UNIT_SP, ts, r.getDisplayMetrics ());

            // set raw text size
            textpaint.setTextSize (ts);

            // see how wide a character is (we are monospaced)
            charWidth  = textpaint.measureText ("M", 0, 1);
            lineHeight = textpaint.getFontMetricsInt (null);

            // call onDraw() with our new size
            requestLayout ();
            invalidate ();
        }

        public int getLineHeight () { return lineHeight; }

        /**
         * Called by Android when it wants to know how much screen area we need.
         */
        @Override
        protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec)
        {
            // get height and width needed (in character units)
            int hchr;
            int wchr;
            synchronized (dcLock) {
                hchr = dirContents.size () + 1;
                wchr = widestLine;
            }

            // set up pixel width and height
            int wpix = (int)Math.ceil (wchr * charWidth);
            int hpix = hchr * lineHeight;
            setMeasuredDimension (wpix, hpix);
        }

        /**
         * The highlight status of the given file has changed.
         * Update the view if it is displayed.
         */
        public void checkHighlight (IFile file)
        {
            synchronized (dcLock) {
                for (ViewedFile vf : dirContents) {
                    if (vf.file.equals (file)) {
                        invalidate ();
                        break;
                    }
                }
            }
        }

        /**
         * If the view dimensions change at all, re-render the text so we have the correct
         * number of characters and lines displayed to fill the screen.
         */
        @Override
        protected void onLayout (boolean changed, int left, int top, int right, int bottom)
        {
            super.onLayout (changed, left, top, right, bottom);
            if (changed) invalidate ();
        }

        /**
         * Do our own drawing so we only have to draw visible lines.
         */
        @Override
        protected void onDraw (Canvas canvas)
        {
            if (canvas.getClipBounds (bounds)) {
                boxpaint.setColor  (fgcolor);
                hlpaint.setColor   (hlcolor);
                textpaint.setColor (fgcolor);
                int underhang = (int) Math.ceil (textpaint.descent ());
                synchronized (dcLock) {
                    ArrayList<ViewedFile> dc = dirContents;
                    int dcNum = dc.size ();
                    int topDispLine = bounds.top    / lineHeight;
                    int botDispLine = bounds.bottom / lineHeight;
                    for (int j = topDispLine; (j <= botDispLine) && (j < dcNum); j ++) {
                        ViewedFile viewedFile = dc.get (j);
                        int y = (j + 1) * lineHeight;
                        String line = viewedFile.getLine (sizeWidth);

                        // draw gray background if file is in list of selected files
                        if (highlightedFiles.contains (viewedFile.file)) {
                            canvas.drawRect (
                                    0.0F,
                                    y - lineHeight + underhang,
                                    charWidth * line.length (),
                                    y + underhang,
                                    hlpaint
                            );
                        }

                        // draw a box around text that is one end of the double-click selection range
                        if (j == lastOneSeld) {
                            canvas.drawRect (
                                    0.0F,
                                    y - lineHeight + underhang,
                                    charWidth * line.length (),
                                    y + underhang,
                                    boxpaint
                            );
                        }

                        // finally draw the line of text
                        canvas.drawText (line, 0.0F, y, textpaint);
                    }
                }
            }
        }

        /**
         * FilesTextViewSV is passing on that there is a motion up event
         * that is for selecting/deselecting files.
         */
        public void motionEventUp (MotionEvent me)
        {
            // see if it's a double-click
            long now = SystemClock.uptimeMillis ();
            if (lastSelTime < now - 1000) {

                // single click, figure out what line was clicked on and get corresponding IFile struct
                float y          = me.getY ();
                float lineHeight = getLineHeight ();
                int lineNumber   = (int)Math.floor (y / lineHeight);
                IFile selFile = null;
                synchronized (dcLock) {
                    if ((lineNumber >= 0) && (lineNumber < dirContents.size ())) {
                        selFile = dirContents.get (lineNumber).file;
                    }
                }

                // select/deselect the file
                if (selFile != null) {
                    lastSelTime = now;
                    lastTwoSeld = lastOneSeld;
                    lastOneSeld = lineNumber;
                    explorerView.onFileSelected (selFile);
                }
            } else {

                // double click, select all files between the last two selected
                ArrayList<IFile> files = null;
                synchronized (dcLock) {
                    if ((lastOneSeld | lastTwoSeld) >= 0) {
                        int lo = Math.min (lastOneSeld, lastTwoSeld);
                        int hi = lastOneSeld + lastTwoSeld - lo + 1;
                        if (hi > dirContents.size ()) hi = dirContents.size ();
                        if (hi > lo) {
                            files = new ArrayList<> (hi - lo);
                            for (int i = lo; i < hi; i ++) {
                                files.add (dirContents.get (i).file);
                            }
                        }
                    }
                }
                if (files != null) explorerView.selectFiles (files, FileExplorerNav.this);
            }
        }

        /**
         * One file being displayed in view.
         */
        private class ViewedFile {
            public IFile file;    // file being displayed

            private String apws;  // absolute path, with slash (if directory)
            private String line;  // formatted line string

            // format the line for output if not already done
            public String getLine (int sizwid)
            {
                if (line == null) {
                    StringBuilder fileRow = new StringBuilder ();

                    String symlink = null;
                    char typeChar, readChar, writeChar;
                    try {
                        typeChar = (symlink = file.getSymLink ()) != null ? 'l' :
                                    file.isDirectory () ? 'd' :
                                    file.isFile () ? '-' : ' ';
                    } catch (IOException ioe) {
                        typeChar = '?';
                    }
                    try {
                        readChar = file.canRead ()  ? 'r' : '-';
                    } catch (IOException ioe) {
                        readChar = '?';
                    }
                    try {
                        writeChar = file.canWrite () ? 'w' : '-';
                    } catch (IOException ioe) {
                        writeChar = '?';
                    }
                    fileRow.append (typeChar);
                    fileRow.append (readChar);
                    fileRow.append (writeChar);
                    fileRow.append ("  ");

                    int fsbeg = fileRow.length ();
                    try {
                        long len = file.length ();
                        fileSizeString (fileRow, len);
                    } catch (IOException ioe) {
                        // eg, dead softlink
                    }
                    int fslen = fileRow.length () - fsbeg;
                    while (spaces.length () < sizwid) spaces += spaces;
                    if (fslen < sizwid) {
                        fileRow.insert (fsbeg, spaces, fslen, sizwid);
                    }

                    try {
                        long modTime = file.lastModified ();
                        datestr.delete (0, datestr.length ());
                        datefmt.format (new Date (modTime), datestr, new FieldPosition (NumberFormat.INTEGER_FIELD));
                        fileRow.append (datestr);
                    } catch (IOException ioe) {
                        // eg, dead softlink
                        fileRow.append (datespac);
                    }

                    String fileName = getAPWS ();
                    if (fileName.startsWith (dirNameAPWS)) fileName = fileName.substring (dirNameAPWS.length ());
                    fileRow.append (fileName);

                    if (symlink != null) {
                        fileRow.append (" -> ");
                        fileRow.append (symlink);
                    }

                    line = fileRow.toString ();
                }
                return line;
            }

            public String getAPWS ()
            {
                if (apws == null) {
                    apws = file.getAPWithSlashNX ();
                }
                return apws;
            }
        }
    }

    /**
     * Append a file size to the given string builder.
     */
    public static void fileSizeString (StringBuilder sb, long fileSize)
    {
        String fsStr = Long.toString (fileSize);
        int fsStrLen = fsStr.length ();
        int nd       = fsStrLen % 3;
        if (nd > 0) {
            sb.append (fsStr, 0, nd);
        }
        while (nd < fsStrLen) {
            if (nd > 0) sb.append (',');
            sb.append (fsStr, nd, nd + 3);
            nd += 3;
        }
    }

    /**
     * Zooming the text.
     */
    private class FilesTextZoom extends PanAndZoom {
        public FilesTextZoom ()
        {
            super (sshclient);
        }

        // called when mouse pressed
        //  x,y = absolute mouse position
        public void MouseDown (float x, float y) { }

        // called when mouse released
        public void MouseUp () { }

        // called when panning
        //  x,y = absolute mouse position
        //  dx,dy = delta position
        public void Panning (float x, float y, float dx, float dy) { }

        // called when scaling
        //  fx,fy = center of scaling
        //  sf = delta scaling factor
        public void Scaling (float fx, float fy, float sf)
        {
            if (txtsize * sf < Settings.TEXT_SIZE_MIN) sf = Settings.TEXT_SIZE_MIN / txtsize;
            if (txtsize * sf > Settings.TEXT_SIZE_MAX) sf = Settings.TEXT_SIZE_MAX / txtsize;

            float deltaScrollX = (sf - 1.0F) * fx;
            float deltaScrollY = (sf - 1.0F) * fy;
            filesTextViewSV.scrollBy ((int) deltaScrollX, 0);
            explorerView.offsetMainScrollerBy ((int) deltaScrollY);

            filesTextView.setTextSize (txtsize * sf);
        }
    }
}
