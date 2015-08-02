/**
 * This widget holds the main screen text.
 *
 * It is an edit text box so the user can focus the keyboard on it when
 * he/she wants to send to the host.
 *
 * Most of the EditText mechanisms are gutted:
 *  1) We intercept all keyboard strokes and do not allow them to alter
 *     the window contents directly, but pass them on to the host for
 *     processing.  The host then sends back any updates it wants to
 *     make to the screen text contents.
 *  2) We provide our own text editing and drawing, as the supplied ones
 *     are too clumsy for our use.  This includes scrolling.  As such,
 *     none of the normal setText() and append() calls work, instead,
 *     Incoming(), Clear() and RenderText() are provided.
 *
 * But we stick with an EditText widget so we get the soft keyboard stuff
 * and all the paint setup.
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


import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;

import com.jcraft.jsch.ChannelShell;

import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenTextView extends KeyboardableView implements SshClient.HasMainMenu {
    public static final String TAG = "SshClient";

    public static final int CURSOR_HALFCYCLE_MS = 512;

    private final ScreenTextBuffer screenTextBuffer;  // where we get shell screen data from

    private AtomicBoolean invalTextImmPend = new AtomicBoolean (false);
    private boolean ctrlkeyflag;                 // when set, next key if 0x40..0x7F gets converted to 0x00..0x1F
    private boolean frozen;                      // indicates frozen mode
    private boolean invalTextDelPend;
    private boolean selectActive;                // the 'b' key has been pressed to begin text selection
    private float savedTextSize;                 // most recently set text size (don't trust getTextSize ())
    private int charWidthInPixels;               // width of one character
    private int lastsentptychrcols;
    private int lastsentptychrrows;
    private int lastsentptypixcols;
    private int lastsentptypixrows;
    private int lineHeightInPixels;              // height of one line of text
    private int numVisibleChars;                 // number of chars (ie width) that can fit in one line of current window
    private int numVisibleLines;                 // number of lines (ie height) that can fit in current window
    private int selectBeg;                       // select range beginning offset in theWholeText
    private int selectCursor;                    // offset in theWholeText where select cursor is
    private int selectEnd;                       // select range end offset in theWholeText
    private int[] precedNewLines  = new int[1];  // offsets of preceding newlines in theWholeText
    private int[] visibleLineBegs = new int[1];  // offsets of beginning of visible lines in theWholeText
    private int[] visibleLineEnds = new int[1];  // offsets of end of visible lines in theWholeText
    private long cursorBlinkBase;                // forces cursor on for any text change
    private MySession session;                   // what session we are part of
    private SshClient sshclient;                 // what activity we are part of
    private ShellEditText edtx;                  // holds the text display
    private STPanning panning;                   // used for scrolling based on mouse movements
    private HorizontalScrollView vt100kb;        // non-null means we are using VT100KBView keyboard

    public ScreenTextView (MySession ms, ScreenTextBuffer stb)
    {
        super (ms.getSshClient ());
        session   = ms;
        sshclient = ms.getSshClient ();
        screenTextBuffer = stb;

        edtx = new ShellEditText ();
        SetEditor (edtx);
        LoadSettings ();

        panning = new STPanning (sshclient);

        screenTextBuffer.SetChangeNotification (new Runnable () {
            @Override
            public void run ()
            {
                if (!invalTextImmPend.getAndSet (true)) {
                    session.getScreendatahandler ().sendEmptyMessage (ScreenDataHandler.INVALTEXT);
                }
            }
        });
    }

    /**
     * User just selected 'shell' mode, so set up the shell mode main menu items.
     */
    @Override  // HasMainMenu
    public void MakeMainMenu ()
    {
        sshclient.SetMMenuItems (
                "Ctrl-...", new Runnable () {
                    public void run ()
                    {
                        ctrlkeyflag = true;
                    }
                },
                "Ctrl-C", new Runnable () {
                    public void run ()
                    {
                        SendCharToHostShell (3);
                    }
                },
                "Freeze", new Runnable () {
                    public void run ()
                    {
                        FreezeThaw ();
                    }
                },
                "Paste", new Runnable () {
                    public void run ()
                    {
                        if (IsFrozen ()) {
                            sshclient.ErrorAlert ("Paste to host", "disabled while screen frozen");
                        } else {
                            sshclient.PasteFromClipboard (new SshClient.PFC () {
                                @Override
                                public void run (String str)
                                {
                                    screenTextBuffer.SendStringToHostShell (str);
                                }
                            });
                        }
                    }
                },
                "Tab", new Runnable () {
                    public void run ()
                    {
                        SendCharToHostShell (9);
                    }
                });
    }

    /**
     * Set up text attributes from settings file.
     */
    public void LoadSettings ()
    {
        Settings settings = sshclient.getSettings ();

        // set up possibly new size
        screenTextBuffer.SetRingSize (settings.max_chars.GetValue ());

        // now that array is in place (in case of redraw triggers herein), set up everything else
        edtx.setBackgroundColor (frozen ? settings.GetFGColor () : settings.GetBGColor ());
        edtx.setTextSize (settings.font_size.GetValue ());

        // select keyboard style
        boolean usevt100kb = settings.vt100_kbd.GetValue ();
        SelectKeyboard (usevt100kb);
    }
    @Override  // KeyboardableView
    protected View GetAltKeyboard ()
    {
        return new VT100KBView (session, this);
    }

    /**
     * Get ready to start a new session.
     */
    public void Reset ()
    {
        // force sending setPty() to host as soon as we know screen dimensions
        lastsentptychrcols = -1;
        lastsentptychrrows = -1;
        lastsentptypixcols = -1;
        lastsentptypixrows = -1;
    }

    /**
     * Freeze the screen, so things like text selection will work.
     * Prevents any updates or scrolling while frozen by blocking receive from the host
     * (there might be a couple more updates draining through).
     */
    public void FreezeThaw ()
    {
        if (!frozen && (charWidthInPixels > 0) && (lineHeightInPixels > 0)) {

            // freeze, halt receiver thread
            frozen = true;
            screenTextBuffer.SetFrozen (true);

            // reverse background color
            Settings settings = sshclient.getSettings ();
            edtx.setBackgroundColor (settings.GetFGColor ());

            // reset select range
            selectActive = false;
            selectBeg = selectCursor = selectEnd = screenTextBuffer.insertpoint;

            // get stuff redrawn in reversed colors
            edtx.invalidate ();
        } else {
            ThawIt ();
        }
    }

    public void ThawIt ()
    {
        // thaw, release receiver thread
        frozen = false;
        screenTextBuffer.SetFrozen (false);

        // reset any active select range
        selectActive = false;
        selectBeg = selectEnd = 0;

        // get stuff redrawn in normal colors
        Settings settings = sshclient.getSettings ();
        edtx.setBackgroundColor (settings.GetBGColor ());
        edtx.invalidate ();
    }

    public boolean IsFrozen () { return frozen; }

    /******************************\
     *  Incoming data processing  *
    \******************************/

    /**
     * Clear the screen.
     */
    public void Clear ()
    {
        if (!frozen) {
            screenTextBuffer.ClearScreen ();
        }
    }

    /**
     * Some text was just received, so tell Android to call onDraw().
     * Or maybe we just want to flip the cursor.
     */
    public void InvalTextReceived ()
    {
        if (!invalTextImmPend.getAndSet (false)) {
            invalTextDelPend = false;
        }
        edtx.invalidate ();
    }

    public void PanCoastReceived ()
    {
        panning.PanCoast ();
    }

    /**
     * Holds the displayed text.
     * Also contains the normal (non-vt-100) keyboard.
     */
    private class ShellEditText extends EditText implements TextWatcher {
        private boolean readingkb;
        private Paint bgpaint;                       // used to paint text background rectangles
        private Paint cursorPaint;                   // used to paint cursor
        private Paint fgpaint;                       // used to paint text characters
        private Paint selectPaint;                   // background paint for selected text
        private Paint showeolPaint;                  // paints the EOL character

        public ShellEditText ()
        {
            super (sshclient);
            addTextChangedListener (this);

            setTypeface (Typeface.MONOSPACE);
            setSingleLine (false);
            setHorizontallyScrolling (false);

            bgpaint = new Paint ();

            cursorPaint = new Paint ();

            fgpaint = getPaint ();
            fgpaint.setStyle (Paint.Style.FILL_AND_STROKE);

            selectPaint = new Paint ();
            selectPaint.setColor (Color.GRAY);
            selectPaint.setStyle (Paint.Style.FILL);

            showeolPaint = new Paint ();
            showeolPaint.setTypeface (Typeface.MONOSPACE);
        }

        /**
         * Intercept any changes in font size so we can recompute pixel sizes.
         */
        @Override
        public void setTextSize (float size)
        {
            super.setTextSize (size);
            savedTextSize      = size;
            charWidthInPixels  = 0;
            lineHeightInPixels = 0;
            screenTextBuffer.needToRender = true;
            invalidate ();
        }

        /**
         * If the view dimensions change at all, re-render the text so we have the correct
         * number of characters and lines displayed to fill the screen.
         */
        @Override
        protected void onLayout (boolean changed, int left, int top, int right, int bottom)
        {
            super.onLayout (changed, left, top, right, bottom);
            if (changed) {
                charWidthInPixels  = 0;
                lineHeightInPixels = 0;
                screenTextBuffer.needToRender = true;
                invalidate ();
            }
        }

        /**
         * Panning (scrolling) the text.
         */
        @Override
        public boolean onTouchEvent (@NonNull MotionEvent me)
        {
            panning.OnTouchEvent (me);
            return super.onTouchEvent (me);
        }

        /**
         * We were invalidated via invalidate(), so draw all the text to the canvas.
         */
        @Override
        protected void onDraw (@NonNull Canvas canvas)
        {
            long uptimemillis = SystemClock.uptimeMillis ();

            synchronized (screenTextBuffer) {

                // maybe need to set up new line mapping
                if (screenTextBuffer.needToRender) {
                    RenderText ();
                    screenTextBuffer.needToRender = false;
                    screenTextBuffer.renderCursor = -1;
                    cursorBlinkBase = uptimemillis;
                }

                // reset cursor blink cycle whenever there was a change
                uptimemillis -= cursorBlinkBase;

                // get ring buffer parameters
                final char[]  twt = screenTextBuffer.twt;
                final int     twb = screenTextBuffer.twb;
                final int     twm = screenTextBuffer.twm;
                final short[] twa = screenTextBuffer.twa;

                // get text size parameters
                int underhang  = (int)Math.ceil (fgpaint.descent ());
                int lineheight = getLineHeight ();
                int top        = getPaddingTop ();
                int left       = getPaddingLeft ();
                int bottom     = getHeight ();
                int right      = getWidth ();

                // get selected text range if any
                int selbeg = (selectBeg <= selectEnd) ? selectBeg : selectEnd;
                int selend = (selectEnd >= selectBeg) ? selectEnd : selectBeg;

                // a vt100 normally has (somewhat) white characters on a black background
                // so we consider it to be reversed when the settings background is white
                // and we reverse that again if we are in frozen mode
                boolean bgrev = (sshclient.getSettings ().GetBGColor () != Color.BLACK) ^ frozen;

                // get cursor position
                int cursor = frozen ? selectCursor : screenTextBuffer.insertpoint;

                // set up a not possible value (cuz we always have alpha != 0)
                int lastdefbgcolor = 0;

                // step through visible lines
                for (int lineno = 0; lineno < numVisibleLines; lineno ++) {

                    // y co-ordinate at bottom of text
                    // (underhang goes below this)
                    top += lineheight;

                    // get offsets in theWholeText to be drawn for the line
                    int precnl  = precedNewLines[lineno];
                    int linebeg = visibleLineBegs[lineno];
                    int lineend = visibleLineEnds[lineno];
                    if (linebeg < 0) continue;  // unwritten lines at top of page have -999 here

                    // set up background color for this line and the rest of the page
                    // by using the background color of the preceding line feed
                    int defbgcolor = screenTextBuffer.attrs;
                    if (precnl >= 0) {
                        defbgcolor = twa[(twb+precnl)&twm];
                    }
                    defbgcolor = bgrev ?
                            ScreenTextBuffer.FGColor (defbgcolor) :
                            ScreenTextBuffer.BGColor (defbgcolor);
                    if (defbgcolor != lastdefbgcolor) {
                        bgpaint.setColor (defbgcolor);
                        canvas.drawRect (left, top + underhang - lineheight, right, bottom, bgpaint);
                        lastdefbgcolor = defbgcolor;
                    }

                    int     stackStart = -1;
                    short   stackAttrs = 0;
                    boolean stackSeld  = false;

                    // step through the line's visible characters
                    for (int charno = linebeg; charno < lineend; charno ++) {

                        // find out about character to be drawn
                        int     index = (twb + charno) & twm;
                        short   attrs = twa[index];
                        boolean seld  = selectActive && (charno >= selbeg) && (charno < selend);

                        // if attributes are different than previous on the line,
                        // draw previous stuff then save this one as first of its kind
                        if ((stackStart < 0) || (attrs != stackAttrs) || (seld != stackSeld)) {
                            if (stackStart >= 0) {
                                int x = left + charWidthInPixels * (stackStart - linebeg);
                                DrawStackedChars (canvas, stackAttrs, stackStart, charno,
                                        uptimemillis, bgrev, stackSeld,
                                        defbgcolor, x, top, underhang,
                                        lineheight, twt, twb, twm);
                            }
                            stackStart = charno;
                            stackAttrs = attrs;
                            stackSeld  = seld;
                        }
                    }

                    // flush any undrawn chars
                    if (stackStart >= 0) {
                        int x = left + charWidthInPixels * (stackStart - linebeg);
                        DrawStackedChars (canvas, stackAttrs, stackStart, lineend,
                                uptimemillis, bgrev, stackSeld,
                                defbgcolor, x, top, underhang,
                                lineheight, twt, twb, twm);
                    }

                    // maybe drawr a cursor
                    if ((cursor >= linebeg) &&                                                   // has to be on or after first char on line
                            (cursor <= lineend) &&                                               // has to be on or before last char on line
                                                                                                 // ...can also be right after last char on line
                            (cursor < linebeg + numVisibleChars) &&                              // can't be off visible width of screen
                            (uptimemillis % (2 * CURSOR_HALFCYCLE_MS) < CURSOR_HALFCYCLE_MS)) {  // make it blink
                        cursorPaint.setColor (bgrev ? ScreenTextBuffer.BGColor (screenTextBuffer.attrs) : ScreenTextBuffer.FGColor (screenTextBuffer.attrs));
                        int x = (cursor - linebeg) * charWidthInPixels + left;
                        switch (sshclient.getSettings ().cursor_style.GetValue ()) {
                            case 0: {  // line
                                canvas.drawLine (x - 2, top + underhang - lineheight, x - 2, top + underhang, cursorPaint);
                                break;
                            }
                            case 1: {  // box
                                cursorPaint.setStyle (Paint.Style.STROKE);
                                canvas.drawRect (x - 2, top + underhang - lineheight, x + charWidthInPixels, top + underhang, cursorPaint);
                                break;
                            }
                            case 2: {  // block
                                cursorPaint.setStyle (Paint.Style.FILL);
                                canvas.drawRect (x - 2, top + underhang - lineheight, x + charWidthInPixels, top + underhang, cursorPaint);
                                break;
                            }
                        }
                    }
                }
            }

            // maybe call back in a a little while to flip cursor on or off or blink text on or off
            if (!invalTextDelPend) {
                invalTextDelPend = true;
                session.getScreendatahandler ().sendEmptyMessageDelayed (
                        ScreenDataHandler.INVALTEXT,
                        CURSOR_HALFCYCLE_MS - (uptimemillis % CURSOR_HALFCYCLE_MS)
                );
            }
        }

        /**
         * Map theWholeText to the screen, using whatever scrolling offsets are in effect.
         * renderCursor < 0: leave scrolling alone
         *             else: adjust scrolling such that cursor position is visible
         */
        private void RenderText ()
        {
            // calculate number of characters that will fit in a single line of the
            // usable area of the screen
            int usableWidth = getWidth () - getPaddingLeft () - getPaddingRight ();
            if (charWidthInPixels == 0) {
                Paint paint = getPaint ();
                charWidthInPixels = (int)(Math.ceil (paint.measureText ("M", 0, 1)));
                if (charWidthInPixels > 0) numVisibleChars = usableWidth / charWidthInPixels;
            }

            // calculate number of lines that will fit on the usable area of screen
            int usableHeight = getHeight () - getPaddingTop () - getPaddingBottom ();
            if (lineHeightInPixels == 0) {
                lineHeightInPixels = getLineHeight ();
                if (lineHeightInPixels > 0) numVisibleLines = usableHeight / lineHeightInPixels;
            }

            // make sure we got something for all that
            // it might be early on and we don't get rational stuff
            if ((usableWidth <= 0) || (charWidthInPixels <= 0) || (numVisibleChars <= 0) ||
                    (usableHeight <= 0) || (lineHeightInPixels <= 0) || (numVisibleLines <= 0)) {
                charWidthInPixels  = 0;
                lineHeightInPixels = 0;
                numVisibleChars    = 0;
                numVisibleLines    = 0;
                return;
            }

            // maybe we need to inform host of a change in screen dimensions
            // also inform ScreenTextBuffer in case it cares
            if ((lastsentptychrcols != numVisibleChars) ||
                    (lastsentptychrrows != numVisibleLines) ||
                    (lastsentptypixcols != usableWidth) ||
                    (lastsentptypixrows != usableHeight)) {
                ChannelShell shellchan = session.getShellChannel ();
                if ((shellchan != null) && !frozen) {
                    lastsentptychrcols = numVisibleChars;
                    lastsentptychrrows = numVisibleLines;
                    lastsentptypixcols = usableWidth;
                    lastsentptypixrows = usableHeight;

                    Log.d (TAG, "setPtySize ("  + lastsentptychrcols + ", " + lastsentptychrrows + ", " + lastsentptypixcols + ", " + lastsentptypixrows + ")");
                    // doesn't throw anything, just discards any errors
                    shellchan.setPtySize (lastsentptychrcols, lastsentptychrrows, lastsentptypixcols, lastsentptypixrows);

                    screenTextBuffer.SetVisibleDims (numVisibleLines, numVisibleChars);
                }
            }

            // these arrays will be filled with the beginning and end of each visible line.
            // it will always have exactly numvisiblelines (possibly with blank lines on the top)
            // and the lines are no longer than numVisibleChars.
            if (visibleLineBegs.length != numVisibleLines) {
                precedNewLines  = new int[numVisibleLines];
                visibleLineBegs = new int[numVisibleLines];
                visibleLineEnds = new int[numVisibleLines];
            }

            // get local copies of this stuff
            final char[] twt = screenTextBuffer.twt;  // theWholeText ring buffer of shell text from the host
            final int    twb = screenTextBuffer.twb;  // the base of all our indices
            final int    twm = screenTextBuffer.twm;  // mask for twt indices (twt.length()-1)
            final int    twu = screenTextBuffer.theWholeUsed;
            final int renderCursor = screenTextBuffer.renderCursor;

            // do initial mapping of text lines to visible lines.
            // vertical scrolling may be adjusted to make cursor line visible.
            // lines are full width of text lines and not horizontally scrolled.
            boolean seencursor = (renderCursor < 0);  // pretend we have seen cursor if we aren't looking
            boolean showeols   = sshclient.getSettings ().show_eols.GetValue ();
            int cursorvisline  = -1;                  // don't know what line cursor is in yet
            int nextlineend    = twu;                 // just past end of last line to be evaluated
            int vislinenum     = numVisibleLines + screenTextBuffer.scrolledLinesDown;

            //  nextlineend > 0 : there is more text to look at
            //  vislinenum  > 0 : there are more visible lines to fill in
            //  !seencursor     : keep going anyway because we haven't seen cursor line yet
            while ((nextlineend > 0) && ((vislinenum > 0) || !seencursor)) {

                // nextlineend = just past what we want to display on this line
                //               never includes the newline char even if showeols mode
                int vislineend = nextlineend;

                // maybe extend line to include the newline as a character on the end
                if (showeols && (vislineend < twu)) {
                    vislineend ++;
                }

                // compute beginning of what to display on this line

                // next loop's end of line is at preceding newline
                while (-- nextlineend >= 0) {
                    char ch = twt[(nextlineend+twb)&twm];
                    if (ch == '\n') break;
                }

                // this line's beginning is just after that newline
                int vislinebeg = nextlineend + 1;

                // maybe cursor is visible in this line
                boolean cursorinthisline = (renderCursor >= vislinebeg) && (renderCursor <= vislineend);

                // maybe we need to scroll text up to see cursor line
                if (cursorinthisline && (vislinenum > numVisibleLines)) {
                    screenTextBuffer.scrolledLinesDown -= vislinenum - numVisibleLines;
                    vislinenum = numVisibleLines;
                }

                // get index to store into
                //   i .ge. numVisibleLines: we are below the scrolled area so skip those lines
                //   i .lt. 0:               we need to scroll text down more to see the cursor
                int i = -- vislinenum;

                // store line limits in array
                if (i < numVisibleLines) {
                    while (i < 0) i += numVisibleLines;
                    precedNewLines[i]  = vislinebeg - 1;
                    visibleLineBegs[i] = vislinebeg;
                    visibleLineEnds[i] = vislineend;
                }

                // remember which line cursor is in
                if (cursorinthisline) cursorvisline = i;

                // remember if cursor has been seen at all
                seencursor |= cursorinthisline;
            }

            if (vislinenum < 0) {

                // text needed to be scrolled down some more to see line with cursor
                // eg, if vislinenum == -1, text needed to be scrolled down one more line
                screenTextBuffer.scrolledLinesDown -= vislinenum;

                // find index in arrays where top line to be displayed is at
                // eg, if vislinenum == -1, top line is at index numVisibleLines - 1
                do vislinenum += numVisibleLines;
                while (vislinenum < 0);

                // rotate that index around to index 0 so it ends up at top
                if (vislinenum > 0) {
                    int[] temp = new int[numVisibleLines];
                    System.arraycopy (precedNewLines, vislinenum, temp, 0, numVisibleLines - vislinenum);
                    System.arraycopy (precedNewLines, 0, temp, numVisibleLines - vislinenum, vislinenum);
                    System.arraycopy (temp, 0, precedNewLines, 0, numVisibleLines);
                    System.arraycopy (visibleLineBegs, vislinenum, temp, 0, numVisibleLines - vislinenum);
                    System.arraycopy (visibleLineBegs, 0, temp, numVisibleLines - vislinenum, vislinenum);
                    System.arraycopy (temp, 0, visibleLineBegs, 0, numVisibleLines);
                    System.arraycopy (visibleLineEnds, vislinenum, temp, 0, numVisibleLines - vislinenum);
                    System.arraycopy (visibleLineEnds, 0, temp, numVisibleLines - vislinenum, vislinenum);
                    System.arraycopy (temp, 0, visibleLineEnds, 0, numVisibleLines);
                }

                // so theoretically cursor is in the top line now
                cursorvisline = 0;
            } else if (vislinenum > 0) {

                // if there are visible lines left at the top, fill them with null lines
                // use some number that can't possibly match a cursor, so any negative would work

                // but it is possible we have blank lines because text is scrolled down,
                // in which case we get rid of that much scrolling down and try again.
                if (screenTextBuffer.scrolledLinesDown > 0) {
                    screenTextBuffer.scrolledLinesDown -= vislinenum;
                    if (screenTextBuffer.scrolledLinesDown < 0) screenTextBuffer.scrolledLinesDown = 0;
                    RenderText ();
                    return;
                }

                if (vislinenum > numVisibleLines) vislinenum = numVisibleLines;
                while (-- vislinenum >= 0) {
                    precedNewLines[vislinenum]  = -999;
                    visibleLineBegs[vislinenum] = -999;
                    visibleLineEnds[vislinenum] = -999;
                }
            }

            // apply horizontal scrolling and line length limit

            // maybe adjust horizontal scrolling to make cursor visible
            if (MakeCursorVisible (cursorvisline, renderCursor)) {

                // get index for beginning of full line
                int vislinebeg = visibleLineBegs[cursorvisline];

                // see how many chars over from beginning of line cursor is
                int cursorinline = renderCursor - vislinebeg;

                // if cursor is scrolled off to the left, scroll text right just enough
                // have one char to the left of cursor visible too if possible
                if (screenTextBuffer.scrolledCharsLeft >= cursorinline) {
                    screenTextBuffer.scrolledCharsLeft = cursorinline;
                    if (screenTextBuffer.scrolledCharsLeft > 0) -- screenTextBuffer.scrolledCharsLeft;
                }

                // if cursor is scrolled off to the right, scroll text left just enough
                if (screenTextBuffer.scrolledCharsLeft <= cursorinline - numVisibleChars) {
                    screenTextBuffer.scrolledCharsLeft = cursorinline - numVisibleChars + 1;
                }
            }

            // otherwise, maybe adjust horizontal scrolling to make *something* visible
            else {

                // get length of longest visible line
                int longestlen = 0;
                for (int i = 0; i < numVisibleLines; i ++) {
                    int linelen = visibleLineEnds[i] - visibleLineBegs[i];
                    if (longestlen < linelen) longestlen = linelen;
                }

                // if the end is scrolled off to the left, scroll text right just enough
                if ((longestlen > 0) && (screenTextBuffer.scrolledCharsLeft >= longestlen)) {
                    screenTextBuffer.scrolledCharsLeft = longestlen - 1;
                }
            }

            // apply horizontal scrolling and line length limit
            for (int i = 0; i < numVisibleLines; i ++) {

                // get indices for full length line (excluding the newline)
                int vislinebeg = visibleLineBegs[i];
                int vislineend = visibleLineEnds[i];

                // maybe text is scrolled over some
                vislinebeg += screenTextBuffer.scrolledCharsLeft;

                // never display more than visible width
                if (vislineend > vislinebeg + numVisibleChars) {
                    vislineend = vislinebeg + numVisibleChars;
                }

                // store possibly modified indices back
                visibleLineBegs[i] = vislinebeg;
                visibleLineEnds[i] = vislineend;
            }
        }

        /**
         * Draw run of characters of common attributes
         * @param canvas = canvas to draw characters on
         * @param attrs = attributes to draw characters with
         * @param runbeg = beginning twt[] index inclusive
         * @param runend = ending twt[] index exclusive
         * @param uptimemillis = used for blinking mode
         * @param bgrev = true iff background/foreground colors are reversed
         * @param selected = true iff the characters are all drawn as selected
         * @param defbgcolor = what color already fills the background
         * @param x = left edge of canvas for first char to be drawn
         * @param y = bottom edge of characters
         * @param underhang = how many pixels chars can hang below 'top'
         * @param lineheight = total line height spacing
         * @param twt = text ring buffer
         * @param twb = base index in ring buffer
         * @param twm = mask of ring buffer size
         */
        private void DrawStackedChars (Canvas canvas, short attrs, int runbeg, int runend,
                                       long uptimemillis, boolean bgrev, boolean selected,
                                       int defbgcolor, int x, int y, int underhang,
                                       int lineheight, char[] twt, int twb, int twm)
        {
            // make sure we have something to draw
            if (runend <= runbeg) return;

            // maybe reverse background/foreground colors
            int bgcolor = ScreenTextBuffer.BGColor (attrs);
            int fgcolor = ScreenTextBuffer.FGColor (attrs);
            if (((attrs & ScreenTextBuffer.TWA_REVER) != 0) ^ bgrev) {
                int q = bgcolor;
                bgcolor = fgcolor;
                fgcolor = q;
            }

            // see if it intersects the current select range
            // use highlighted background color if so
            if (selected) {
                bgcolor = Color.rgb (
                        (Color.red   (bgcolor) + Color.red   (fgcolor)) / 2,
                        (Color.green (bgcolor) + Color.green (fgcolor)) / 2,
                        (Color.blue  (bgcolor) + Color.blue  (fgcolor)) / 2
                );
            }

            // draw solid rectangle for background color
            if (bgcolor != defbgcolor) {
                bgpaint.setColor (bgcolor);
                canvas.drawRect (
                        x,
                        y + underhang - lineheight,
                        x + charWidthInPixels * (runend - runbeg),
                        y + underhang,
                        bgpaint
                );
            }

            // maybe skip blinking chars
            if (((attrs & ScreenTextBuffer.TWA_BLINK) != 0) &&
                    (uptimemillis % (2 * CURSOR_HALFCYCLE_MS) < CURSOR_HALFCYCLE_MS)) return;

            // maybe attributes want bolding and/or underlining
            fgpaint.setColor (fgcolor);
            int bold = ((attrs & ScreenTextBuffer.TWA_BOLD) != 0) ? 3 : 1;
            if ((attrs & ScreenTextBuffer.TWA_UNDER) != 0) {
                fgpaint.setStrokeWidth (bold * 2);
                canvas.drawLine (
                        x, y + underhang - 1,
                        x + charWidthInPixels * (runend - runbeg), y + underhang - 1,
                        fgpaint
                );
            }
            fgpaint.setStrokeWidth (bold);

            showeolPaint.setColor (fgcolor);
            showeolPaint.setTextSize (fgpaint.getTextSize () / 2);
            showeolPaint.setStrokeWidth (bold);

            // maybe there is a newline char on the end for show_eols mode
            // newlines can only be on the end because they delimit lines
            // ...and our caller processes things one line at a time
            if (twt[(twb+runend-1)&twm] == '\n') {
                DrawSmallCharPair (canvas, "NL", x + charWidthInPixels * (-- runend - runbeg), y);
            }

            // now drawr the printable characters
            if (runend > runbeg) {
                if ((attrs & ScreenTextBuffer.TWA_SPCGR) != 0) {
                    DrawSpecialGraphics (canvas, runbeg, runend, x, y, twt, twb, twm);
                } else {
                    int indexbeg = (twb + runbeg) & twm;
                    int indexend = (twb + runend - 1) & twm;
                    if (indexend >= indexbeg) {
                        canvas.drawText (twt, indexbeg, runend - runbeg, x, y, fgpaint);
                    } else {
                        canvas.drawText (twt, indexbeg, twm + 1 - indexbeg, x, y, fgpaint);
                        x += charWidthInPixels * (twm + 1 - indexbeg);
                        canvas.drawText (twt, 0, indexend + 1, x, y, fgpaint);
                    }
                }
            }
        }

        /**
         * See if we should scroll horizontally to make the cursor visible.
         * Normally we do it if the cursor is on a visible line.
         * But if the cursor is just off the end of the last visible line,
         * we won't scroll over for it, cuz that technique is used by the top
         * command to park the cursor off the end of the last line to hide it.
         */
        private boolean MakeCursorVisible (int cursorvisline, int renderCursor)
        {
            // if cursor not on a visible line, don't bother scrolling
            if (cursorvisline < 0) return false;

            // if cursor other than at the very end of the ring, make it visible
            if (screenTextBuffer.insertpoint < screenTextBuffer.theWholeUsed) return true;

            // get index for beginning of full line
            int vislinebeg = visibleLineBegs[cursorvisline];

            // see how many chars over from beginning of line cursor is
            int cursorinline = renderCursor - vislinebeg;

            // if cursor is parked right at the end of a line
            // do not scroll over one character to show the cursor
            // (such is the case for the top command output)
            return cursorinline != numVisibleChars;
        }

        /**
         * Draw a run of special graphics characters that weren't handled by ScreenTextBuffer
         * converting them to equivalent Unicode characters.
         */
        private void DrawSpecialGraphics (Canvas canvas, int beg, int end, int x, int y, char[] twt, int twb, int twm)
        {
            do {
                char ch = twt[(twb+beg)&twm];
                switch (ch) {
                    case 0142: DrawSmallCharPair (canvas, "HT", x, y); break;
                    case 0143: DrawSmallCharPair (canvas, "FF", x, y); break;
                    case 0144: DrawSmallCharPair (canvas, "CR", x, y); break;
                    case 0145: DrawSmallCharPair (canvas, "LF", x, y); break;
                    case 0150: DrawSmallCharPair (canvas, "NL", x, y); break;
                    case 0151: DrawSmallCharPair (canvas, "VT", x, y); break;

                    // checkerboard
                    case 0141: {
                        canvas.save ();
                        try {
                            Paint.FontMetricsInt fm = fgpaint.getFontMetricsInt ();
                            int top = y + fm.ascent;
                            canvas.clipRect (x, top, x + charWidthInPixels, y);
                            canvas.drawText ("\u2592", 0, 1, x, y, fgpaint);
                        } finally {
                            canvas.restore ();
                        }
                        break;
                    }

                    // horizontal lines
                    //   0157 at the top of the cell
                    //   0161 in the middle (as drawn by unicode 0x2500)
                    //   0163 at the bottom of the cell
                    case 0157:case 0160:case 0162:case 0163: {
                        // compute offset to draw unicode 0x2500 with
                        int underhang = (int)Math.ceil (fgpaint.descent ());
                        int dy = 0161 - ch;
                        int yy = y - (lineHeightInPixels - underhang) * dy / 4;
                        canvas.drawText ("\u2500", 0, 1, x, yy, fgpaint);
                        break;
                    }
                }
                x += charWidthInPixels;
            } while (++ beg < end);
        }

        /**
         * Draw pair of small-sized characters in a single character cell.
         * @param canvas = what to draw them on
         * @param pair = pair of characters to draw
         * @param x1 = left edge of char cell
         * @param y2 = bottom edge of char cell
         */
        private void DrawSmallCharPair (Canvas canvas, String pair, int x1, int y2)
        {
            y2 += showeolPaint.descent ();

            int y1 = y2 - lineHeightInPixels / 2;
            canvas.drawText (pair, 0, 1, x1, y1, showeolPaint);

            int x2 = x1 + charWidthInPixels / 2;
            canvas.drawText (pair, 1, 2, x2, y2, showeolPaint);
        }

        // process enter and delete keys
        @Override
        public boolean dispatchKeyEvent (@NonNull KeyEvent ke)
        {
            if (ke.getAction () == KeyEvent.ACTION_DOWN) {
                switch (ke.getKeyCode ()) {
                    case KeyEvent.KEYCODE_ENTER: {
                        ProcessKeyboardString ("\r");
                        return true;
                    }
                    case KeyEvent.KEYCODE_DEL: {
                        ProcessKeyboardString ("\177");
                        return true;
                    }
                }
            }
            return super.dispatchKeyEvent (ke);
        }

        /*
         * All this bologna to capture printable keys...
         *
         * TextWatcher sequence is:
         *   beforeTextChanged() once
         *   onTextChanged() twice!
         *   afterTextChanged() once
         */

        @Override // TextWatcher
        public void beforeTextChanged (CharSequence s, int start, int count, int after)
        { }

        @Override // TextWatcher
        public void onTextChanged (CharSequence s, int start, int before, int count)
        { }

        @Override // TextWatcher
        public void afterTextChanged (Editable s)
        {
            if (!readingkb) {
                readingkb = true;
                String str = s.toString ();
                s.replace (0, str.length (), "", 0, 0);
                ProcessKeyboardString (str);
                readingkb = false;
            }
        }
    }

    private class STPanning extends PanAndZoom {
        private boolean coasting;
        private boolean isDown;
        private float startx, starty;
        private int startscrolledcharsleft;
        private int startscrolledlinesdown;

        private long  mousedownat_t, mousemoveat_t;
        private float mousedownat_x, mousemoveat_x;
        private float mousedownat_y, mousemoveat_y;

        public STPanning (Context ctx)
        {
            super (ctx);
        }

        // called when mouse pressed
        //  x,y = absolute mouse position
        public void MouseDown (float x, float y)
        {
            isDown = true;

            // remember where the user put his/her finger down originally
            startx = x;
            starty = y;
            startscrolledcharsleft = screenTextBuffer.scrolledCharsLeft;
            startscrolledlinesdown = screenTextBuffer.scrolledLinesDown;

            // remember where and when for coasting purposes
            mousedownat_t = 0;
            mousedownat_x = 0.0F;
            mousedownat_y = 0.0F;
            mousemoveat_t = SystemClock.uptimeMillis ();
            mousemoveat_x = x;
            mousemoveat_y = y;

            // if frozen, move selection cursor here
            if (frozen && (charWidthInPixels > 0) && (lineHeightInPixels > 0)) {
                int charno = (int)x / charWidthInPixels;
                int lineno = (int)y / lineHeightInPixels;
                if (lineno < 0) lineno = 0;
                if (lineno >= numVisibleLines) lineno = numVisibleLines - 1;
                if (charno < 0) charno = 0;
                selectCursor = charno + visibleLineBegs[lineno];
                if (selectCursor > visibleLineEnds[lineno]) selectCursor = visibleLineEnds[lineno];
                if (selectActive) selectEnd = selectCursor;
            }
        }

        // called when mouse released
        public void MouseUp ()
        {
            isDown = false;
            if (!coasting) {
                coasting = true;
                session.getScreendatahandler ().sendEmptyMessageDelayed (ScreenDataHandler.PANCOAST, 50);
            }
        }

        // called after a short delay after mouse released
        // keep the scrolling going at a decayed rate until it slows to a stop
        public void PanCoast ()
        {
            coasting = false;
            if (!isDown && (mousemoveat_t > mousedownat_t)) {
                float ratio  = 0.875F * (float)(SystemClock.uptimeMillis () - mousemoveat_t) / (float)(mousemoveat_t - mousedownat_t);
                float new_dx = (mousemoveat_x - mousedownat_x) * ratio;
                float new_dy = (mousemoveat_y - mousedownat_y) * ratio;
                float new_x  = mousemoveat_x + new_dx;
                float new_y  = mousemoveat_y + new_dy;
                Panning (new_x, new_y, new_dx, new_dy);
                if (new_dx * new_dx + new_dy * new_dy > 0.250F * ((float)charWidthInPixels * (float)charWidthInPixels + (float)lineHeightInPixels * (float)lineHeightInPixels)) {
                    coasting = true;
                    session.getScreendatahandler ().sendEmptyMessageDelayed (ScreenDataHandler.PANCOAST, 50);
                }
            }
        }

        // called when panning
        //  x,y = absolute mouse position
        //  dx,dy = delta position
        public void Panning (float x, float y, float dx, float dy)
        {
            mousedownat_t = mousemoveat_t;
            mousedownat_x = mousemoveat_x;
            mousedownat_y = mousemoveat_y;
            mousemoveat_t = SystemClock.uptimeMillis ();
            mousemoveat_x = x;
            mousemoveat_y = y;

            boolean render = false;
            if (charWidthInPixels > 0) {
                int deltacharsleft = (int)(startx - x) / charWidthInPixels;
                int scl = startscrolledcharsleft + deltacharsleft;
                if (scl < 0) scl = 0;
                if (screenTextBuffer.scrolledCharsLeft != scl) {
                    screenTextBuffer.scrolledCharsLeft = scl;
                    render = true;
                }
            }
            if (lineHeightInPixels > 0) {
                int deltalinesdown = (int)(y - starty) / lineHeightInPixels;
                int sld = startscrolledlinesdown + deltalinesdown;
                if (sld < 0) sld = 0;
                if (screenTextBuffer.scrolledLinesDown != sld) {
                    screenTextBuffer.scrolledLinesDown = sld;
                    render = true;
                }
            }
            if (render) {
                screenTextBuffer.needToRender = true;
                edtx.invalidate ();
            }
        }

        // called when scaling
        //  fx,fy = absolute position of center of scaling
        //  sf = delta scaling factor
        public void Scaling (float fx, float fy, float sf)
        {
            float ts = savedTextSize * sf;
            if (ts < Settings.TEXT_SIZE_MIN) ts = Settings.TEXT_SIZE_MIN;
            if (ts > Settings.TEXT_SIZE_MAX) ts = Settings.TEXT_SIZE_MAX;
            edtx.setTextSize (ts);
        }
    }

    /******************************\
     *  Outgoing data processing  *
    \******************************/

    // process menu and back keys, volume up/down
    //@Override
    //public boolean onKeyPreIme (int keyCode, KeyEvent ke)
    //{
    //    Log.d (TAG, "onKeyPreIme*: keyCode=" + keyCode + " ke=" + ((ke == null) ? "null" : ke.toString ()));
    //    return super.onKeyPreIme (keyCode, ke);
    //}

    /**
     * Process a string coming from the keyboard (normal or vt-100).
     * @param str = keyboard string to process, eg, "A"
     */
    public void ProcessKeyboardString (String str)
    {
        for (char chr : str.toCharArray ()) {
            if (ctrlkeyflag && (chr >= 0x40) && (chr <= 0x7F)) chr = (char) (chr & 0x1F);
            ctrlkeyflag = false;
            if (frozen) {
                ProcessSelectionChar (chr);
            } else {
                SendCharToHostShell (chr);
            }
        }
    }

    /**
     * Send keyboard character(s) on to host for processing.
     * Don't send anything while frozen because the remote host might be blocked
     * and so the TCP link to the host is blocked and so we would block.
     */
    private void SendCharToHostShell (int code)
    {
        char[] array = new char[] { (char)code };
        if (!screenTextBuffer.SendStringToHostShell (new String (array))) {
            SshClient.MakeBeepSound ();
        }
    }

    /**
     * Keyboard characters entered while frozen are used to navigate text selection.
     */
    private void ProcessSelectionChar (char ch)
    {
        switch (ch) {
            case 'B':
            case 'b': {  // begin
                selectBeg = selectEnd = selectCursor;
                selectActive = true;
                break;
            }
            case 'C':
            case 'c': {  // copy
                if (selectBeg != selectEnd) {
                    CopyToClipboard ();
                }
                break;
            }
            case 'I':
            case 'i': {  // up
                int lineno = screenTextBuffer.TextOffsetToLineNumber (selectCursor);
                int charno = screenTextBuffer.TextOffsetToCharNumber (selectCursor);
                int newlen = screenTextBuffer.TextLineLength (--lineno);
                if (charno > newlen) charno = newlen;
                selectCursor = screenTextBuffer.LineCharNumberToTextOffset (lineno, charno);
                break;
            }
            case 'J':
            case 'j': {  // left
                if (selectCursor > 0) -- selectCursor;
                break;
            }
            case 'K':
            case 'k': {  // down
                int lineno = screenTextBuffer.TextOffsetToLineNumber (selectCursor);
                int charno = screenTextBuffer.TextOffsetToCharNumber (selectCursor);
                int newlen = screenTextBuffer.TextLineLength (++lineno);
                if (charno > newlen) charno = newlen;
                selectCursor = screenTextBuffer.LineCharNumberToTextOffset (lineno, charno);
                break;
            }
            case 'L':
            case 'l': {  // right
                if (selectCursor < screenTextBuffer.theWholeUsed) selectCursor ++;
                break;
            }
            case 'R':
            case 'r': {  // reset
                selectActive = false;
                selectBeg = selectEnd = selectCursor;
                break;
            }
            default: {
                Log.w (TAG, "ignoring keyboard char <" + ch + "> while frozen");
                SshClient.MakeBeepSound ();
                break;
            }
        }
        if (selectActive) selectEnd = selectCursor;

        screenTextBuffer.needToRender = true;
        screenTextBuffer.renderCursor = selectCursor;
        edtx.invalidate ();
    }

    /**
     * Maybe send the selected character sequence to the clipboard.
     */
    private void CopyToClipboard ()
    {
        // get substring that was selected
        final char[] twt = screenTextBuffer.twt;
        final int    twb = screenTextBuffer.twb;
        final int    twm = screenTextBuffer.twm;
        int beg = (selectBeg <= selectEnd) ? selectBeg : selectEnd;
        int end = (selectEnd >= selectBeg) ? selectEnd : selectBeg;
        int len = end - beg;
        beg = (twb + beg) & twm;
        end = (twb + end - 1) & twm;
        final String subseq = (end >= beg) ?
                new String (twt, beg, len) :
                new String (twt, beg, twm + 1 - beg) + new String (twt, 0, ++ end);

        // display alert box that copies string to the clipboard
        sshclient.CopyToClipboard (subseq, new Runnable () {
            @Override
            public void run ()
            {
                selectActive = false;
                edtx.invalidate ();
            }
        });
    }
}
