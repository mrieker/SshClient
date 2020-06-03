/**
 * Ring buffer that holds shell screen data as received from host.
 * It lives in the background as part of JSessionService so the
 * ScreenDataThread has some place to put data received from the
 * host while detached.  Upon reattach, the GUI (ScreenTextView)
 * can get the data from it.
 *
 * The only link it contains to the GUI components is the 'notify'
 * link that tells the GUI that there is new data for it to process,
 * and that link is cleared when the GUI detaches., thus allowing
 * the GUI to be garbage collected.  Meanwhile the ScreenDataThread
 * will continue to fill it as part of JSessionService.
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


import android.graphics.Color;
import android.util.Log;

import java.io.OutputStreamWriter;

@SuppressWarnings("unused")
public class ScreenTextBuffer {
    public static final String TAG = "SshClient";

    public char[] twt;              // theWholeText array ring buffer
    public int twb;                 // base of all other indices
    public int twm;                 // twt.length - 1 (always power-of-two - 1)
    public int tws;                 // twt.length (always power-of-two)
    public short[] twa;             // theWholeAttr array ring buffer

    public boolean needToRender;    // next onDraw() needs to create theWholeText->visibleLine{Beg,End}s{} mapping
    public int renderCursor;        // next onDraw() needs to adjust scrolling to make this cursor visible

    private boolean frozen;         // indicates frozen mode
    public  int insertpoint;        // where next incoming text gets inserted in theWholeText
    private int lineWidth;          // number of characters per line of latest GUI ScreenTextView
    private int screenHeight;       // number of lines per screen of latest GUI ScreenTextView
    public  int scrolledCharsLeft;  // number of chars we are shifted left
    public  int scrolledLinesDown;  // number of lines we are shifted down
    public  int theWholeUsed;       // amount in theWholeText that is occupied starting at twb
    private MySession session;
    private Runnable notify;        // what to notify when there are changes

    private static final short C_BLACK   = 0;
    private static final short C_RED     = 1;
    private static final short C_GREEN   = 2;
    private static final short C_YELLOW  = 3;
    private static final short C_BLUE    = 4;
    private static final short C_MAGENTA = 5;
    private static final short C_CYAN    = 6;
    private static final short C_WHITE   = 7;

    public static final short TWA_BOLD  =   1;
    public static final short TWA_UNDER =   2;
    public static final short TWA_BLINK =   4;
    public static final short TWA_REVER =   8;
    public static final short TWA_BGCLR =  16 * 7;
    public static final short TWA_FGCLR = 128 * 7;
    public static final short TWA_SPCGR = 1024;

    @SuppressWarnings("PointlessArithmeticExpression")
    public static final short RESET_ATTRS = (TWA_FGCLR & -TWA_FGCLR) * C_WHITE + (TWA_BGCLR & -TWA_BGCLR) * C_BLACK;

    private static final int ESCSEQMAX = 32;

    public final static int[] colorTable = new int[] {
            Color.BLACK, Color.RED, Color.GREEN, Color.YELLOW,
            Color.BLUE, Color.MAGENTA, Color.CYAN, Color.WHITE };

    private boolean altcharset;
    public  boolean cursorappmode;    // false: send <ESC>[ABCD; true: send <ESC>OABCD for arrow keys
    private boolean hardwrapmode;     // as given by escape sequence from host
    private boolean hardwrapsetting;  // as given by the settings screen
    public  boolean keypadappmode;    // false: keypad sends numbers; true: keypad sends escape sequences
    private boolean linefeedmode;     // false: <LF> stays in same column; true: <LF> goes to first col in new line
    private boolean originmode;       // false: cursor positioning absolute; true: cursor positioning relative to margin
    private boolean savedAltCharSet;
    private boolean[] tabstops;       // positions of tabs
    private final char[] escseqbuf = new char[ESCSEQMAX];
    private int   botScrollLine;
    private int   escseqidx;
    private int   savedColNumber;
    private int   savedRowNumber;
    private int   topScrollLine;
    public  short attrs = RESET_ATTRS;

    public ScreenTextBuffer (MySession s)
    {
        session = s;
        LoadSettings ();
        PowerOnReset ();
    }

    /**
     * Send keyboard character(s) on to host shell for processing.
     * Don't send anything while frozen because the remote host might be blocked
     * and so the TCP link to the host is blocked and so we would block.
     */
    public boolean SendStringToHostShell (String txt)
    {
        ScreenDataThread screendatathread = session.getScreendatathread ();
        if (screendatathread == null) {
            Log.w (TAG, "send to host discarded while disconnected");
            return false;
        }
        if (frozen) {
            Log.w (TAG, "send to host discarded while frozen");
            return false;
        }
        OutputStreamWriter out = screendatathread.output;
        if (out == null) {
            Log.w (TAG, "send to host discarded while logged out");
            return false;
        }
        try {
            out.write (txt);
            out.flush ();
        } catch (Exception e) {
            Log.w (TAG, "transmit error", e);
            ScreenMsg ("\r\ntransmit error: " + SshClient.GetExMsg (e));
            try { out.close (); } catch (Exception ignored) { }
            screendatathread.output = null;
            return false;
        }
        return true;
    }

    /**
     * Return all host settable settings to power-on reset state.
     */
    private void PowerOnReset ()
    {
        altcharset      = false;
        cursorappmode   = false;
        hardwrapmode    = true;
        keypadappmode   = false;
        linefeedmode    = false;
        originmode      = false;
        savedAltCharSet = false;
        tabstops        = new boolean[lineWidth];
        botScrollLine   = screenHeight;
        escseqidx       = -1;
        savedColNumber  = 1;
        savedRowNumber  = 1;
        topScrollLine   = 1;

        for (int i = 0; i < lineWidth; i += 8) {
            tabstops[i] = true;
        }
    }

    /**
     * Set what screen the text is being displayed on so
     * we know what to notify when there are updates.
     */
    public void SetChangeNotification (Runnable not)
    {
        notify = not;
    }

    /**
     * A setting has been changed.  Get the ones we care about.
     */
    public void LoadSettings ()
    {
        synchronized (this) {

            // hardwrapsetting = true: no horizontal scrolling on the GUI display
            //                         all lines we generate in the ring are lineWidth max length
            //                         hardwrapmode = false: discard chars after lineWidth length
            //                                         true: start a separate line after lineWidth length
            //                         (behaves like an XTerm of a fixed width)
            //                  false: we can possibly have lines longer than lineWidth in ring buffer
            //                         GUI will allow horizontal scrolling
            //                         hardwrapmode is ignored
            //                         (behaves like something of indefinite width)
            hardwrapsetting = session.getSshClient ().getSettings ().wrap_lines.GetValue ();
        }
    }

    /**
     * Put a short message in screen ring buffer for display.
     * Ignores frozen mode so long messages may annoy user.
     */
    public void ScreenMsg (String msg)
    {
        synchronized (this) {
            IncomingNF (msg.toCharArray (), 0, msg.length ());
        }
    }

    /**
     * Set new ring buffer size.
     */
    public void SetRingSize (int newtws)
    {
        // round new size up to power-of-two
        while ((newtws & -newtws) != newtws) {
            newtws += newtws & -newtws;
        }

        synchronized (this) {

            // realloc the array if rounded-up size different
            if (newtws != tws) {
                char[] newtwt  = new char[newtws];
                short[] newtwa = new short[newtws];

                int newtwm = newtws - 1;                   // bitmask for wrapping indices
                int newtwu = 0;                            // no array elements used yet
                int newtwb = 0;                            // start of used elements is beginning of array
                for (int i = 0; i < theWholeUsed; i ++) {  // step through each used char in old array
                    newtwt[(newtwb+newtwu)&newtwm] = twt[(twb+i)&twm];
                    newtwa[(newtwb+newtwu)&newtwm] = twa[(twb+i)&twm];
                    if (newtwu < newtws) newtwu ++;        // if not yet full, it has one more char now
                    else newtwb = (newtwb + 1) & newtwm;   // otherwise, old char on front was overwritten
                }

                insertpoint += newtwu - theWholeUsed;
                if (insertpoint < 0) insertpoint = 0;

                twt = newtwt;
                twa = newtwa;
                twb = newtwb;
                twm = newtwm;
                tws = newtws;
                theWholeUsed = newtwu;
            }
        }
    }

    /**
     * Set frozen mode.
     * Blocks screen updates while set.
     */
    public void SetFrozen (boolean frz)
    {
        synchronized (this) {
            frozen = frz;
            if (!frozen) notifyAll ();
        }
    }

    /**
     * Clear screen.
     */
    public void ClearScreen ()
    {
        synchronized (this) {
            theWholeUsed      = 0;
            insertpoint       = 0;
            scrolledCharsLeft = 0;
            scrolledLinesDown = 0;
            needToRender      = true;

            Runnable not = notify;
            if (not != null) not.run ();
        }
    }

    /**
     * Find the offset in the text buffer for a given character of a given line.
     * @param lineno = line number starting at 0
     * @param charno = character within that line, starting at 0
     * @return offset in text buffer for that character
     */
    public int LineCharNumberToTextOffset (int lineno, int charno)
    {
        if (lineno < 0) return 0;
        int i;
        for (i = 0; (i < theWholeUsed) && (lineno > 0); i ++) {
            if (twt[(twb+i)&twm] == '\n') -- lineno;
        }
        if (lineno > 0) return theWholeUsed;
        i += charno;
        if (i < 0) return 0;
        return Math.min (i, theWholeUsed);
    }

    /**
     * Get the length of the given line.
     * @param lineno = line number starting at 0
     * @return number of characters in line not including \n
     */
    public int TextLineLength (int lineno)
    {
        if (lineno < 0) return 0;
        int i, j;
        for (i = 0; (i < theWholeUsed) && (lineno > 0); i ++) {
            if (twt[(twb+i)&twm] == '\n') -- lineno;
        }
        if (lineno > 0) return 0;
        for (j = i; j < theWholeUsed; j ++) {
            if (twt[(twb+j)&twm] == '\n') break;
        }
        return j - i;
    }

    /**
     * Find out which line a given offset is in the text buffer.
     * @param offs = offset starting at 0
     * @return line number starting at 0
     */
    public int TextOffsetToLineNumber (int offs)
    {
        int lineno = 0;
        for (int i = 0; i < offs; i ++) {
            if (twt[(twb+i)&twm] == '\n') lineno ++;
        }
        return lineno;
    }

    /**
     * Find out which character within a line the given offset is.
     * @param offs = offset starting at 0
     * @return character number starting at 0
     */
    public int TextOffsetToCharNumber (int offs)
    {
        int lastbol = 0;
        for (int i = 0; i < offs; i ++) {
            if (twt[(twb+i)&twm] == '\n') lastbol = i + 1;
        }
        return offs - lastbol;
    }

    /**
     * Set what the host thinks our dimensions are.
     * @param rows = number of rows the host thinks we have
     * @param cols = number of columns the host thinks we have
     */
    public void SetVisibleDims (int rows, int cols)
    {
        synchronized (this) {
            screenHeight  = rows;
            lineWidth     = cols;
            topScrollLine = 1;
            botScrollLine = rows;

            boolean[] newtabstops = new boolean[cols];
            for (int i = 0; i < cols; i ++) {
                newtabstops[i] = (i < tabstops.length) ? tabstops[i] : (i % 8 == 0);
            }
            tabstops = newtabstops;
        }
    }

    /**
     * Process incoming shell data and format into ring buffer.
     */
    public void Incoming (char[] buf, int beg, int end)
    {
        synchronized (this) {

            // block here if user has frozen the display
            while (frozen) {
                try {
                    wait ();
                } catch (InterruptedException ie) {
                    Log.w (TAG, "interrupted while frozen", ie);
                }
            }

            // not frozen, update ring buffer
            IncomingNF (buf, beg, end);
        }
    }

    private void IncomingNF (char[] buf, int beg, int end)
    {
        // do the updates
        IncomingWork (buf, beg, end);

        // update on-screen display (if any attached)
        needToRender = true;
        renderCursor = insertpoint;

        Runnable not = notify;
        if (not != null) not.run ();
    }

    private void IncomingWork (char[] buf, int beg, int end)
    {
        /*{
            StringBuffer sb = new StringBuffer ();
            for (int j = beg; j < end; j ++) {
                if (sb.length () >= 64) {
                    WriteDebug ("from host '" + sb.toString () + "'");
                    sb.delete (0, sb.length ());
                }
                char c = buf[j];
                if ((c >= 32) && (c != '\\') && (c != 127)) sb.append (c);
                else switch (c) {
                    case '\b': sb.append ("\\b"); break;
                    case '\n': sb.append ("\\n"); break;
                    case '\r': sb.append ("\\r"); break;
                    case '\t': sb.append ("\\t"); break;
                    case '\\': sb.append ("\\\\"); break;
                    default: {
                        sb.append ('\\');
                        for (int i = 9; (i -= 3) >= 0;) {
                            sb.append ((char) ('0' + ((c >> i) & 7)));
                        }
                        break;
                    }
                }
            }
            WriteDebug ("from host '" + sb.toString () + "'");
        }*/

        // look for control characters in the given text.
        // insert all the other text by appending to existing text.
        // the resulting theWholeText should only contain printables
        // and newline characters.
        for (int i = beg; i < end; i ++) {
            char ch = buf[i];

            // any control character aborts escape sequence
            if (ch < 32) escseqidx = -1;

            // if not doing an escape sequence, store char in ring buffer
            if (escseqidx < 0) {
                ControlOrPrintable (ch);
            } else {

                // doing escape sequence, store char on end of escape buffer
                if (escseqidx < ESCSEQMAX) escseqbuf[escseqidx++] = ch;

                // process the sequence if end of escape sequence
                if (escseqbuf[0] != '[') {
                    if (ch >= 48) Escaped ();
                } else {
                    if ((ch >= 64) && (escseqidx > 1)) Escaped ();
                }
            }
        }
    }

    /**
     * Process an incoming control or printable character not part of an escape sequence.
     */
    @SuppressWarnings("OctalInteger")
    private void ControlOrPrintable (char ch)
    {
        if (ch < 32) {

            // process the control character
            switch (ch) {

                // bell
                case 7: {
                    session.getSshClient ().MakeBeepSound ();
                    break;
                }

                // backspace: back up the insertion point one character,
                // but not before beginning of current line
                // has to work as part of BS/' '/BS sequence to delete last char on line
                case 8: {
                    if (insertpoint > 0) {
                        int bol = BegOfLine (insertpoint);
                        if (insertpoint > bol) {
                            ch = twt[(twb+insertpoint-1)&twm];
                            if ((ch == ' ') && (insertpoint == theWholeUsed)) {
                                -- theWholeUsed;
                            }
                            -- insertpoint;
                        }
                    }
                    break;
                }

                // tab: space to next tabstop position in line
                case 9: {
                    int colofs = insertpoint - BegOfLine (insertpoint);
                    do StorePrintableAtInsertPoint (' ', attrs);
                    while ((++ colofs < tabstops.length) ? !tabstops[colofs] : (colofs % 8 != 0));
                    break;
                }

                // newline (linefeed): advance insertion point to end of line and append newline
                // also reset horizontal scrolling in case a prompt is incoming next
                // has to work as part of CR/LF sequence
                case 10:    // line feed
                case 11:    // vertical tab
                case 12: {  // form feed
                    if (linefeedmode) {
                        NextLine ();
                        scrolledCharsLeft = 0;
                    } else {
                        int colofs = insertpoint - BegOfLine (insertpoint);
                        NextLine ();
                        MoveCursorRight (colofs);
                    }
                    break;
                }

                // carriage return: backspace to just after most recent newline
                // has to work as part of CR/LF sequence
                case 13: {
                    insertpoint = BegOfLine (insertpoint);
                    scrolledCharsLeft = 0;
                    break;
                }

                case 14: {  // SO (shift out)
                    altcharset = true;
                    break;
                }

                case 15: {  // SI (shift in)
                    altcharset = false;
                    break;
                }

                // start an escape sequence
                case 27: {
                    escseqidx = 0;
                    break;
                }

                // who knows - ignore all other control characters
                default: {
                    Log.d (TAG, "ignoring incoming " + (int) ch);
                    break;
                }
            }
        } else if (ch != 127) {
            short at = attrs;
            if (altcharset && (ch >= 0137) && (ch <= 0176)) {
                // http://unicode-table.com/en
                switch (ch) {
                    case 0137: ch = 0x25AF; break;    // open block
                    case 0140: ch = 0x25C6; break;    // solid diamond
                    case 0146: ch = 0x00B0; break;    // degree symbol
                    case 0147: ch = 0x00B1; break;    // plus/minus
                    case 0171: ch = 0x2264; break;    // less than/equal
                    case 0172: ch = 0x2265; break;    // greater than/equal
                    case 0173: ch = 0x03C0; break;    // pi
                    case 0174: ch = 0x2260; break;    // not equal
                    case 0175: ch = 0x00A3; break;    // british pounds
                    case 0176: ch = 0x00B7; break;    // center dot

                    case 0152: ch = 0x2518; break;    // block drawing
                    case 0153: ch = 0x2510; break;
                    case 0154: ch = 0x250C; break;
                    case 0155: ch = 0x2514; break;
                    case 0156: ch = 0x253C; break;
                    case 0161: ch = 0x2500; break;
                    case 0164: ch = 0x251C; break;
                    case 0165: ch = 0x2524; break;
                    case 0166: ch = 0x2534; break;
                    case 0167: ch = 0x252C; break;
                    case 0170: ch = 0x2502; break;

                    default: at |= TWA_SPCGR; break;  // no unicode equivalent, defer to ScreenTextView
                }
            }
            StorePrintableAtInsertPoint (ch, at);
        }
    }

    /**
     * Process VT-100 escape sequence in escseqbuf.
     * http://ascii-table.com/ansi-escape-sequences-vt-100.php
     * http://www.ccs.neu.edu/research/gpc/MSim/vona/terminal/VT100_Escape_Codes.html
     */
    private void Escaped ()
    {
        String escseqstr = new String (escseqbuf, 0, escseqidx);
        //WriteDebug ("escape seq '" + escseqstr + "'");

        int escseqlen = escseqidx;
        char termchr = escseqbuf[--escseqlen];
        escseqidx = -1;

        // don't bother if we don't know the screen height yet
        if (screenHeight <= 0) return;

        if (escseqbuf[0] != '[') {
            switch (termchr) {
                case '7': {
                    savedRowNumber  = GetLineNumber (insertpoint);
                    savedColNumber  = insertpoint - GetLineOffset (savedRowNumber) + 1;
                    savedAltCharSet = altcharset;
                    break;
                }
                case '8': {
                    SetCursorPosition (savedRowNumber, savedColNumber);
                    altcharset  = savedAltCharSet;
                    break;
                }
                case '=': {
                    keypadappmode = true;
                    break;
                }
                case '>': {
                    keypadappmode = false;
                    break;
                }

                // go down, if at bottom margin, scroll
                // stay in same column
                case 'D': {
                    int colofs = insertpoint - BegOfLine (insertpoint);
                    NextLine ();
                    MoveCursorRight (colofs);
                    break;
                }

                // go down, if at bottom margin, scroll
                // also go to beginning of new line
                case 'E': {
                    NextLine ();
                    break;
                }

                // set tab stop
                case 'H': {
                    int colofs = insertpoint - BegOfLine (insertpoint);
                    if (colofs < tabstops.length) tabstops[colofs] = true;
                    break;
                }

                // go up, if at top margin, scroll
                // stay in same column
                case 'M': {
                    int colofs = insertpoint - BegOfLine (insertpoint);
                    PrevLine ();
                    MoveCursorRight (colofs);
                    break;
                }

                // reset
                case 'c': {
                    PowerOnReset ();
                    ClearScreen ();
                    SetCursorPosition (1, 1);
                    break;
                }

                default: {
                    Log.d (TAG, "unhandled escape seq '" + escseqstr + "'");
                    break;
                }
            }
        } else {
            String[] parms;
            try {
                parms = new String (escseqbuf, 1, escseqlen - 1).split (";");
            } catch (Exception e) {
                parms = new String[] { "" };
            }
            switch (termchr) {

                // [ A - move cursor up
                case 'A': {
                    int nlines = ParseInt (parms[0]);
                    if (nlines <= 0) nlines = 1;

                    // get line number we are currently on
                    int currentLineNumber = GetLineNumber (insertpoint);
                    if (currentLineNumber > 0) {

                        // get beginning of current line
                        int currentBegOfLine = GetLineOffset (currentLineNumber);

                        // compute current column number (zero based)
                        int currentColumnNum = insertpoint - currentBegOfLine;

                        // compute target line number
                        int targetLineNumber = currentLineNumber - nlines;
                        if (targetLineNumber < topScrollLine) targetLineNumber = topScrollLine;

                        // get beginning of target line
                        insertpoint = GetLineOffset (targetLineNumber);

                        // now move it over the original number of columns
                        MoveCursorRight (currentColumnNum);
                    }
                    break;
                }

                // [ B - move cursor down
                case 'B': {
                    int nlines = ParseInt (parms[0]);
                    if (nlines <= 0) nlines = 1;

                    // get line number we are currently on
                    int currentLineNumber = GetLineNumber (insertpoint);
                    if (currentLineNumber > 0) {

                        // get beginning of current line
                        int currentBegOfLine = GetLineOffset (currentLineNumber);

                        // compute current column number (zero based)
                        int currentColumnNum = insertpoint - currentBegOfLine;

                        // compute target line number
                        int targetLineNumber = currentLineNumber + nlines;
                        if (targetLineNumber > botScrollLine) targetLineNumber = botScrollLine;

                        // get beginning of target line
                        insertpoint = GetLineOffset (targetLineNumber);

                        // now move it over the original number of columns
                        MoveCursorRight (currentColumnNum);
                    }
                    break;
                }

                // [ C - move cursor right
                case 'C': {
                    int nchars = ParseInt (parms[0]);
                    if (nchars <= 0) nchars = 1;
                    MoveCursorRight (nchars);
                    break;
                }

                // [ D - move cursor left
                case 'D': {
                    int nchars = ParseInt (parms[0]);
                    int bol = BegOfLine (insertpoint);
                    while (insertpoint > bol) {
                        -- insertpoint;
                        if (-- nchars <= 0) break;
                    }
                    break;
                }

                // [ H and [ f - position cursor
                case 'H':
                case 'f': {
                    int row = ParseInt (parms[0]);
                    int col = (parms.length > 1) ? ParseInt (parms[1]) : 0;
                    if (originmode) {
                        row += topScrollLine - 1;
                    }
                    SetCursorPosition (row, col);
                    break;
                }

                // [ J - erase in display
                case 'J': {
                    int currentLineNumber = GetLineNumber (insertpoint);
                    if (currentLineNumber > 0) {
                        int code = ParseInt (parms[0]);

                        // 0 or 2: erase characters to end-of-display
                        //     replace them with newlines of current attributes
                        if ((code == 0) || (code == 2)) {
                            theWholeUsed = insertpoint;
                            while (currentLineNumber < screenHeight) {
                                twt[(twb+theWholeUsed)&twm] = '\n';
                                twa[(twb+theWholeUsed)&twm] = attrs;
                                theWholeUsed ++;
                                currentLineNumber ++;
                            }
                        }

                        // 1 or 2 : erase characters up to and including cursor char
                        //     replace them with newlines of current attributes
                        if ((code == 1) || (code == 2)) {

                            // point to beginning of current line
                            int begOfCurrentOffset = GetLineOffset (currentLineNumber);

                            // overwrite current line up to and including cursor with spaces
                            for (int i = begOfCurrentOffset; i <= insertpoint; i ++) {
                                if (twt[(twb+i)&twm] == '\n') break;
                                twt[(twb+i)&twm] = ' ';
                                twa[(twb+i)&twm] = attrs;
                            }

                            // replace lines above current line with newlines
                            int topOfScreenOffset = GetLineOffset (1);
                            for (int i = 0; ++ i < currentLineNumber;) {
                                twt[(twb+topOfScreenOffset)&twm] = '\n';
                                twa[(twb+topOfScreenOffset)&twm] = attrs;
                                topOfScreenOffset ++;
                            }

                            // move stuff starting with current line down over all erased garbage
                            insertpoint -= begOfCurrentOffset - topOfScreenOffset;
                            while (begOfCurrentOffset < theWholeUsed) {
                                int ncopy = theWholeUsed - begOfCurrentOffset;
                                int tcopy = tws - ((twb + topOfScreenOffset)  & twm);
                                int bcopy = tws - ((twb + begOfCurrentOffset) & twm);
                                if (ncopy > tcopy) ncopy = tcopy;
                                if (ncopy > bcopy) ncopy = bcopy;
                                System.arraycopy (twt, (twb + begOfCurrentOffset) & twm,
                                                  twt, (twb + topOfScreenOffset)  & twm, ncopy);
                                topOfScreenOffset  += ncopy;
                                begOfCurrentOffset += ncopy;
                            }
                            theWholeUsed = topOfScreenOffset;
                        }
                    }
                    break;
                }

                // [ K - erase in line
                case 'K': {
                    int code = ParseInt (parms[0]);

                    // 0 or 2 : erase characters to end-of-line
                    //     delete chars starting with cursor up to next newline
                    //     but always leave the next newline intact
                    if ((code == 0) || (code == 2)) {
                        int j = EndOfLine (insertpoint);

                        // if erasing a beginning or middle segment of a wrapped line,
                        // overwrite the erased characters with spaces
                        if ((j < theWholeUsed) && (twt[(twb+j)&twm] != '\n')) {
                            int k = EndOfLine (j);
                            while (j < k) {
                                twt[(twb+j)&twm] = ' ';
                                twa[(twb+j)&twm] = attrs;
                                j ++;
                            }
                        } else {

                            // erasing to end-of-buffer or to next newline,
                            // actually remove the chars from the buffer
                            DeleteCharsAtInsertPoint (j - insertpoint);

                            // set attrs of preceding newline so ScreenTextView knows what
                            // background color to pad the remainder of this line with
                            for (int i = insertpoint; -- i >= 0;) {
                                if (twt[(twb+i)&twm] == '\n') {
                                    twa[(twb+i)&twm] = attrs;
                                    break;
                                }
                            }
                        }
                    }

                    // 1 or 2 : erase characters up to and including cursor char
                    //     replace them with spaces of current attributes
                    if ((code == 1) || (code == 2)) {
                        int i = insertpoint;
                        if ((i >= 0) && (twt[(twb+i)&twm] != '\n')) i ++;
                        for (int j = BegOfLine (i); j < i; j ++) {
                            twt[(twb+j)&twm] = ' ';
                            twa[(twb+j)&twm] = attrs;
                        }
                    }
                    break;
                }

                // [ c - what are you?
                case 'c': {
                    SendStringToHostShell ("\033[?1;0c");
                    break;
                }

                // [ g - clear tabs
                case 'g': {
                    int code = ParseInt (parms[0]);
                    switch (code) {
                        case 0: {
                            int colofs = insertpoint - BegOfLine (insertpoint);
                            if (colofs < tabstops.length) tabstops[colofs] = false;
                            break;
                        }
                        case 3: {
                            for (int i = tabstops.length; -- i >= 0;) {
                                tabstops[i] = false;
                            }
                            break;
                        }
                    }
                    break;
                }

                // [ h - enable something
                // [ l - disable something
                case 'h':
                case 'l': {
                    boolean set = (termchr == 'h');
                    for (String s : parms) {
                        switch (s) {
                            case "20":
                                linefeedmode = set;
                                break;
                            case "?1":
                                cursorappmode = set;
                                break;
                            case "?6":
                                originmode = set;
                                break;
                            case "?7":
                                hardwrapmode = set;
                                break;
                            default:
                                Log.d (TAG, "unhandled escape seq '" + escseqstr + "'");
                                break;
                        }
                    }
                    break;
                }

                // [ m - select graphic rendition
                case 'm': {
                    for (String s : parms) {
                        int n = ParseInt (s);
                        switch (n) {
                            case 0: {
                                attrs = RESET_ATTRS;
                                break;
                            }
                            case 1: {
                                attrs |= TWA_BOLD;
                                break;
                            }
                            case 4: {
                                attrs |= TWA_UNDER;
                                break;
                            }
                            case 5: {
                                attrs |= TWA_BLINK;
                                break;
                            }
                            case 7: {
                                attrs |= TWA_REVER;
                                break;
                            }
                            case 30:case 31:case 32:case 33:case 34:case 35:case 36:case 37: {
                                attrs &= ~TWA_FGCLR;
                                attrs |= (TWA_FGCLR & -TWA_FGCLR) * (n - 30);
                                break;
                            }
                            case 40:case 41:case 42:case 43:case 44:case 45:case 46:case 47: {
                                attrs &= ~TWA_BGCLR;
                                attrs |= (TWA_BGCLR & -TWA_BGCLR) * (n - 40);
                                break;
                            }
                            default: Log.d (TAG, "unhandled escape seq '" + escseqstr + "'");
                        }
                    }
                    break;
                }

                // [ n - device status report
                case 'n': {
                    int param = ParseInt (parms[0]);
                    switch (param) {
                        case 5: {
                            SendStringToHostShell ("\033[0n");
                            break;
                        }
                        case 6: {
                            int row = GetLineNumber (insertpoint);
                            int col = insertpoint - GetLineOffset (row) + 1;
                            if (originmode) {
                                row -= topScrollLine - 1;
                            }
                            SendStringToHostShell ("\033[" + row + ";" + col + "R");
                        }
                        default: Log.d (TAG, "unhandled escape seq '" + escseqstr + "'");
                    }
                    break;
                }

                // [ r - set scrolling region
                case 'r': {
                    topScrollLine = ParseInt (parms[0]);
                    botScrollLine = (parms.length > 1) ? ParseInt (parms[1]) : screenHeight;
                    if (topScrollLine <  1) topScrollLine = 1;
                    if (topScrollLine >= screenHeight) topScrollLine = screenHeight - 1;
                    if (botScrollLine <= topScrollLine) botScrollLine = topScrollLine + 1;
                    if (botScrollLine >  screenHeight) botScrollLine = screenHeight;
                    SetCursorPosition (originmode ? topScrollLine : 1, 1);
                    break;
                }

                default: {
                    Log.d (TAG, "unhandled escape seq '" + escseqstr + "'");
                    break;
                }
            }
        }
    }

    /**
     * GetCode a single integer parameter for the escape sequence.
     */
    private static int ParseInt (String s)
    {
        try {
            return Integer.parseInt (s);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Move cursor down one line.  If at bottom margin, scroll.
     * Then move to beginning of the new line.
     */
    private void NextLine ()
    {
        // move cursor just past last char of current line,
        // not moving it at all if it's already there.
        // we might end up pointing at:
        // 1) the end of the ring buffer
        // 2) a newline char
        insertpoint = EndOfLine (insertpoint);

        // see if at end of ring buffer or on last line of scroll region
        if ((insertpoint >= theWholeUsed) || ((screenHeight > 0) && (GetLineNumber (insertpoint) == botScrollLine))) {

            // scrolling required...

            // insert a newline to give us a new blank line
            // and increment cursor to put it at beginning of that new blank line
            InsertSomethingAtInsertPoint ('\n', attrs);

            // because GetLineNumber() counts from the end backward,
            // it should think we are on the same line number because we
            // are still pointing at the same char as was returned by the
            // EndOfLine() call above.

            // but there is one more line above us now on the screen.
            // if the top scroll is other than the first line, we need
            // to delete the top scroll line from the screen.
            if (topScrollLine > 1) {
                int delbeg = GetLineOffset (topScrollLine - 1);
                int delend = GetLineOffset (topScrollLine);
                DeleteCharsAtArbitraryPoint (delbeg, delend);
            }
        } else {

            // no scrolling required
            // we are pointing to the newline at the end of this line
            // - so we need to hop over the newline to get to next line
            insertpoint ++;
        }
    }

    /**
     * Move cursor up one line.  If at top margin, scroll.
     * Then move to beginning of new line.
     */
    private void PrevLine ()
    {
        // move cursor to beginning of current line,
        // not moving at all if it's already there.
        // we might end up pointing at:
        // 1) the beginning of the ring buffer
        // 2) just past a newline ending the line above us
        insertpoint = BegOfLine (insertpoint);

        // see if at beginning of ring buffer or on first line of scroll region
        if ((insertpoint <= 0) || ((screenHeight > 0) && (GetLineNumber (insertpoint) == topScrollLine))) {

            // scrolling required...

            //  topScrollLine-1: abc<LF>
            //  topScrollLine:   def<LF>
            //       . . .
            //  botScrollLine-1: uvw<LF>
            //  botScrollLine:   xyz

            //  insertpoint points to the 'd' of topScrollLine

            // insert a newline just before the beginning of this line
            // then point to that newline
            InsertSomethingAtInsertPoint ('\n', attrs);
            -- insertpoint;

            //  topScrollLine-2: abc<LF>
            //  topScrollLine-1: <LF>
            //  topScrollLine:   def<LF>
            //       . . .
            //  botScrollLine-1: uvw<LF>
            //  botScrollLine:   xyz

            //  insertpoint points to the '<LF>' of topScrollLine-1

            // delete the botScrollLine, eg, <LF>xyz from the above example
            int delbeg = EndOfLine (GetLineOffset (botScrollLine - 1));
            int delend = EndOfLine (GetLineOffset (botScrollLine));
            DeleteCharsAtArbitraryPoint (delbeg, delend);

            //  topScrollLine-1: abc<LF>
            //  topScrollLine:   <LF>
            //  topScrollLine+1: def<LF>
            //       . . .
            //  botScrollLine:   uvw

            //  insertpoint still points to the lone '<LF>' but it's now at topScrollLine

        } else {

            // no scrolling required
            // we are pointing just past the newline at the end of the previous line
            // - so we need to hop over the newline to get to previous line
            insertpoint = BegOfLine (-- insertpoint);
        }
    }

    /**
     * Move cursor to given absolute row and column.
     */
    private void SetCursorPosition (int row, int col)
    {
        if (screenHeight > 0) {
            if (row <= 0) row = 1;
            if (col <= 0) col = 1;

            // put cursor at beginning of requested line
            if (row > screenHeight) row = screenHeight;
            insertpoint = GetLineOffset (row);

            // move cursor to the right for desired column
            MoveCursorRight (col - 1);
        }
    }

    /**
     * Move cursor (insertpoint) right on current line the given number of columns.
     * Extend the ring buffer if necessary.
     */
    private void MoveCursorRight (int ncols)
    {
        while (-- ncols >= 0) {

            // if about to step over a newline, insert a space at this point
            // use attrs of preceding newline cuz that is what line gets for background color
            if ((insertpoint >= theWholeUsed) || (twt[(twb+insertpoint)&twm] == '\n')) {
                int bol = BegOfLine (insertpoint);
                short at = (bol > 0) ? twa[(twb+bol-1)&twm] : RESET_ATTRS;
                InsertSomethingAtInsertPoint (' ', at);
            } else {

                // about to space over a printable, just step over it
                insertpoint ++;
            }
        }
    }

    /**
     * Store a printable character (not newline) at the cursor position and increment cursor.
     * Break up long lines if hardwrap enabled.
     * @param ch = character to store
     * @param at = corresponding attributes
     */
    private void StorePrintableAtInsertPoint (char ch, short at)
    {
        // maybe simply overwriting existing printable with the given printable
        int i = (twb + insertpoint) & twm;
        if ((insertpoint < theWholeUsed) && (twt[i] != '\n')) {
            twt[i] = ch;
            twa[i] = at;
            insertpoint ++;
            return;
        }

        // about to write onto the end of an existing line

        // if indefinite width display, just insert character on end of existing line
        if (!hardwrapsetting || (lineWidth <= 0)) {
            InsertSomethingAtInsertPoint (ch, at);
            return;
        }

        // fixed width display, just insert character on end if line is short enough
        int bol = BegOfLine (insertpoint);
        if (insertpoint - bol < lineWidth) {
            InsertSomethingAtInsertPoint (ch, at);
            return;
        }

        // line is full width, insert <LF>char if in hardwrap mode
        // otherwise discard the new char
        if (hardwrapmode) {
            if (insertpoint < theWholeUsed) {

                // there is already an <LF> in the ring at insertpoint,
                // so just step over it then store character just after it.
                insertpoint ++;
                StorePrintableAtInsertPoint (ch, at);
            } else {

                // end of existing ring buffer, tack a newline and character on the end
                InsertSomethingAtInsertPoint ('\n', at);
                InsertSomethingAtInsertPoint (ch, at);
            }
        }
    }

    /**
     * Insert character at insert point and increment pointer past it.
     * Extend ring buffer as needed or drop oldest char if necessary.
     * @param ch = character to store
     * @param at = corresponding attributes
     */
    private void InsertSomethingAtInsertPoint (char ch, short at)
    {
        // if the ring buffer is completely full, remove the oldest char
        if (theWholeUsed >= tws) {

            // pop oldest char by incrementing base index
            twb = (twb + 1) & twm;

            // now there is one fewer character in ring
            -- theWholeUsed;

            // if the new char was going where the old one was popped,
            // we're done as is cuz we just popped the new char too
            if (insertpoint <= 0) return;

            // somewhere farther down in ring, point to same char in ring
            -- insertpoint;
        }

        // move everything down one spot to make room
        for (int i = ++ theWholeUsed; -- i > insertpoint;) {
            twt[(twb+i)&twm] = twt[(twb+i-1)&twm];
            twa[(twb+i)&twm] = twa[(twb+i-1)&twm];
        }

        // stpre new character and attributes
        twt[(twb+insertpoint)&twm] = ch;
        twa[(twb+insertpoint)&twm] = at;

        // advance cursor past new character
        insertpoint ++;
    }

    /**
     * Delete the given number of characters starting at the insert point.
     */
    private void DeleteCharsAtInsertPoint (int nch)
    {
        DeleteCharsAtArbitraryPoint (insertpoint, insertpoint + nch);
    }

    private void DeleteCharsAtArbitraryPoint (int delbeg, int delend)
    {
        int nch = delend - delbeg;
        if (nch > 0) {
            theWholeUsed -= nch;
            int twu = theWholeUsed;
            for (int i = delbeg; i < twu; i ++) {
                twt[(twb+i)&twm] = twt[(twb+i+nch)&twm];
                twa[(twb+i)&twm] = twa[(twb+i+nch)&twm];
            }
            if (insertpoint >= delend) insertpoint -= nch;
        }
    }

    /**
     * Get offset at beginning of the given visible line
     * @param lineno = visible line number (1..screenHeight)
     * @return offset of first character of the line
     */
    private int GetLineOffset (int lineno)
    {
        if (screenHeight > 0) {

            // screen line number being scanned (1..screenHeight)
            int i = screenHeight;

            // scan through ring buffer starting from the end
            for (int j = theWholeUsed; j > 0; -- j) {

                // see if this is first char of line
                if (twt[(twb+j-1)&twm] == '\n') {

                    // j = first char of line

                    // if this is the line requested, return its start index
                    if (i == lineno) return j;

                    // not the requested line, start scanning previous line
                    -- i;
                }
            }

            // if ring buffer holds 'abcd', ie, just one line of text
            //     and screenHeight = 3 (i is also 3 cuz 'abcd' doesn't have any newlines)
            //     and lineno = 1,
            // prepend 2 newlines to ring buffer
            while ((i > lineno) && (theWholeUsed < tws)) {
                if (twb == 0) twb = tws;
                twt[--twb] = '\n';
                twa[twb] = RESET_ATTRS;
                theWholeUsed ++;
                insertpoint ++;
                -- i;
            }
        }
        return 0;
    }

    /**
     * Compute the visible line number of a given character in the buffer.
     * @param offset = which character to find line number of
     * @return .le.0: not in visible area
     *          else: line number in range 1..screenHeight
     */
    private int GetLineNumber (int offset)
    {
        if (screenHeight <= 0) return 0;

        int i = screenHeight;
        for (int j = theWholeUsed; j > 0; -- j) {
            if (twt[(twb+j-1)&twm] == '\n') {
                if (j <= offset) return i;
                -- i;
            }
        }
        return i;
    }

    /**
     * Get offset at end of line (just past last printable character of line)
     * @param o = offset somewhere in line (maybe already at beginning)
     * @return offset at end of line (maybe offset of the newline)
     */
    private int EndOfLine (int o)
    {
        while (o < theWholeUsed) {
            if (twt[(twb+o)&twm] == '\n') break;
            o ++;
        }
        return o;
    }

    /**
     * Get offset at beginning of line (first printable character of line)
     * @param o = offset somewhere in line (maybe already at beginning)
     * @return offset at beginning of line (maybe right after prev line's newline)
     */
    private int BegOfLine (int o)
    {
        while (o > 0) {
            if (twt[(twb+o-1)&twm] == '\n') break;
            -- o;
        }
        return o;
    }

    /*
    private void DumpRingBuffer ()
    {
        WriteDebug ("DumpRingBuffer: insertpoint=" + insertpoint + " theWholeUsed=" + theWholeUsed);
        int j;
        for (int i = 0; i < theWholeUsed; i = ++ j) {
            StringBuilder sb = new StringBuilder ();
            for (j = i; j < theWholeUsed; j ++) {
                char c = twt[(twb+j)&twm];
                if (c == '\n') {
                    sb.append ("<LF>");
                    break;
                }
                if (hardwrapmode && (lineWidth > 0) && (j > i) && ((j - i) % lineWidth == 0)) {
                    sb.append ('|');
                }
                sb.append (c);
            }
            int l = GetLineNumber (i);
            WriteDebug ("DumpRingBuffer:" + IntStr (i, 6) + ":" + IntStr (l, 5) + ": <" + sb.toString () + ">");
        }
        j = 0;
        for (int i = 0; i <= theWholeUsed; i ++) {
            int k = GetLineNumber (i);
            if (j < k) {
                WriteDebug ("DumpRingBuffer:" + IntStr (k, 5) + ":" + IntStr (i, 6));
                j = k;
            }
        }
    }

    private void RenderAsciiArt ()
    {
        for (int i = 0; ++ i <= screenHeight;) {
            int begofs = -999;
            int endofs = -999;
            int begidx = -999;
            int endidx = -999;
            try {
                begofs = GetLineOffset (i);
                endofs = EndOfLine (begofs);
                begidx = (begofs + twb) & twm;
                endidx = (endofs + twb - 1) & twm;
                if ((endofs == begofs) || (endidx >= begidx)) {
                    WriteDebug ("AsciiArt" + IntStr (i, 3) + IntStr (begidx, 6) + ".." + IntStr (endidx, 6) + " <" +
                            new String (twt, begidx, endofs - begofs) + ">");
                } else {
                    WriteDebug ("AsciiArt" + IntStr (i, 3) + IntStr (begidx, 6) + ".." + IntStr (endidx, 6) + " <" +
                            new String (twt, begidx, tws - begidx) + new String (twt, 0, endidx - twm) + ">");
                }
            } catch (Exception e) {
                WriteDebug ("AsciiArt" + IntStr (i, 3) + IntStr (begidx, 6) + ".." + IntStr (endidx, 6) + " " + e.getMessage ());
            }
        }
    }

    private static String IntStr (int v, int l)
    {
        String s = Integer.toString (v);
        while (s.length () < l) s = " " + s;
        return s;
    }

    private static java.io.PrintWriter pwdebug;
    private static void WriteDebug (String line)
    {
        if (pwdebug == null) {
            try {
                pwdebug = new java.io.PrintWriter ("/sdcard/screentextbuffer.log");
            } catch (java.io.IOException ioe) {
                Log.w (TAG, "error creating /sdcard/screentextbuffer.log", ioe);
                return;
            }
        }
        pwdebug.println (line);
        pwdebug.flush ();
    }
    */

    /**
     * Get background and foreground color from attributes.
     */
    public static int BGColor (int at)
    {
        return colorTable[(at&ScreenTextBuffer.TWA_BGCLR)/(ScreenTextBuffer.TWA_BGCLR&-ScreenTextBuffer.TWA_BGCLR)];
    }
    public static int FGColor (int at)
    {
        return colorTable[(at&ScreenTextBuffer.TWA_FGCLR)/(ScreenTextBuffer.TWA_FGCLR&-ScreenTextBuffer.TWA_FGCLR)];
    }
}
