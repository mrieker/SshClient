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

public class ScreenTextBuffer {
    public static final String TAG = "SshClient";

    private final static char[] eightspaces = { ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ' };

    public char[] twt;              // theWholeText array ring buffer
    public int twb;                 // base of all other indices
    public int twm;                 // twt.length - 1 (always power-of-two - 1)
    public int tws;                 // twt.length (always power-of-two)
    public short[] twa;             // theWholeAttr array ring buffer

    public boolean needToRender;    // next onDraw() needs to create theWholeText->visibleLine{Beg,End}s{} mapping
    public int renderCursor;        // next onDraw() needs to adjust scrolling to make this cursor visible

    private boolean frozen;         // indicates frozen mode
    public  int insertpoint;        // where next incoming text gets inserted in theWholeText
    public  int scrolledCharsLeft;  // number of chars we are shifted left
    public  int scrolledLinesDown;  // number of lines we are shifted down
    public  int theWholeUsed;       // amount in theWholeText that is occupied starting at twb
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

    public static final short RESET_ATTRS = (TWA_FGCLR & -TWA_FGCLR) * C_WHITE + (TWA_BGCLR & -TWA_BGCLR) * C_BLACK;

    private static final int ESCSEQMAX = 32;

    public final static int[] colorTable = new int[] {
            Color.BLACK, Color.RED, Color.GREEN, Color.YELLOW,
            Color.BLUE, Color.MAGENTA, Color.CYAN, Color.WHITE };

    private final char[] escseqbuf = new char[ESCSEQMAX];
    private int escseqidx = -1;
    public  short attrs = RESET_ATTRS;

    /**
     * Set what screen the text is being displayed on so
     * we know what to notify when there are updates.
     */
    public void SetChangeNotification (Runnable not)
    {
        notify = not;
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
        if (i > theWholeUsed) return theWholeUsed;
        return i;
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
        if (not != null) not.run ();;
    }

    private void IncomingWork (char[] buf, int beg, int end)
    {
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
                Printable (ch);
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

    private void Printable (char ch)
    {
        if (ch >= 32) {
            InsertPrintableAtInsertPoint (ch, attrs);
        } else {

            // process the control character
            switch (ch) {

                // bell
                case 7: {
                    SshClient.MakeBeepSound ();
                    break;
                }

                // backspace: back up the insertion point one character,
                // but not before beginning of current line
                // has to work as part of BS/' '/BS sequence to delete last char on line
                case 8: {
                    if (insertpoint > 0) {
                        ch = twt[(twb+insertpoint-1)&twm];
                        if (ch != '\n') {
                            if ((ch == ' ') && (insertpoint == theWholeUsed)) {
                                -- theWholeUsed;
                            }
                            -- insertpoint;
                        }
                    }
                    break;
                }

                // tab: space to next 8-character position in line
                case 9: {
                    int k;
                    for (k = insertpoint; k > 0; -- k) {
                        if (twt[(twb+k-1)&twm] == '\n') break;
                    }
                    k = (insertpoint - k) % 8;
                    IncomingWork (eightspaces, 0, 8 - k);
                    break;
                }

                // newline (linefeed): advance insertion point to end of line and append newline
                // also reset horizontal scrolling in case a prompt is incoming next
                // has to work as part of CR/LF sequence
                case 10:    // line feed
                case 11:    // vertical tab
                case 12: {  // form feed
                    while (insertpoint < theWholeUsed) {
                        if (twt[(twb+insertpoint)&twm] == '\n') break;
                        insertpoint ++;
                    }
                    if (insertpoint < theWholeUsed) {
                        insertpoint ++;
                    } else {
                        OverwriteSomethingAtInsertPoint ('\n', attrs);
                    }
                    scrolledCharsLeft = 0;
                    break;
                }

                // carriage return: backspace to just after most recent newline
                // has to work as part of CR/LF sequence
                case 13: {
                    while (insertpoint > 0) {
                        ch = twt[(twb+insertpoint-1)&twm];
                        if (ch == '\n') break;
                        -- insertpoint;
                    }
                    scrolledCharsLeft = 0;
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
        }
    }

    // oz_dev_vgavideo_486.c escseqend()
    private void Escaped ()
    {
        String escseqstr = new String (escseqbuf, 0, escseqidx);
        //Log.d (TAG, "escape seq '" + escseqstr + "'");

        char termchr = escseqbuf[--escseqidx];
        escseqbuf[escseqidx] = 0;
        escseqidx = -1;

        if (escseqbuf[0] != '[') {
            Log.d (TAG, "unhandled escape seq '" + escseqstr + "'");
        } else {
            switch (termchr) {

                // [ A - move cursor up
                case 'A': {
                    int nlines = EscSeqInt (escseqbuf, 1);
                    // move temp cursor to beginning of current line
                    int cur = insertpoint;
                    while ((cur > 0) && (twt[(twb+cur-1)&twm] != '\n')) -- cur;
                    // compute the column number it was in
                    int col = insertpoint - cur;
                    // move temp cursor backward to beginning of target line
                    while (cur > 0) {
                        do -- cur;
                        while ((cur > 0) && (twt[(twb+cur-1)&twm] != '\n'));
                        if (-- nlines <= 0) break;
                    }
                    // now move it over the original number of columns
                    insertpoint = cur;
                    while (-- col >= 0) {
                        if (twt[(twb+insertpoint)&twm] == '\n') {
                            InsertPrintableAtInsertPoint (' ', twa[(twb+insertpoint)&twm]);
                        } else {
                            insertpoint ++;
                        }
                    }
                    break;
                }

                // [ B - move cursor down
                case 'B': {
                    int nlines = EscSeqInt (escseqbuf, 1);
                    // move temp cursor to beginning of current line
                    int cur = insertpoint;
                    while ((cur > 0) && (twt[(twb+cur-1)&twm] != '\n')) -- cur;
                    // compute the column number it was in
                    int col = insertpoint - cur;
                    // move cursor forward to beginning of target line
                    insertpoint = cur;
                    while (cur < theWholeUsed) {
                        do cur ++;
                        while ((cur < theWholeUsed) && (twt[(twb+cur-1)&twm] != '\n'));
                        if (twt[(twb+cur-1)&twm] != '\n') break;
                        insertpoint = cur;
                        if (-- nlines <= 0) break;
                    }
                    // now move it over the original number of columns
                    while (-- col >= 0) {
                        if (twt[(twb+insertpoint)&twm] == '\n') {
                            InsertPrintableAtInsertPoint (' ', twa[(twb+insertpoint)&twm]);
                        } else {
                            insertpoint ++;
                        }
                    }
                    break;
                }

                // [ C - move cursor right
                case 'C': {
                    int nchars = EscSeqInt (escseqbuf, 1);
                    do {
                        if ((insertpoint >= theWholeUsed) || (twt[(twb+insertpoint)&twm] == '\n')) {
                            InsertPrintableAtInsertPoint (' ', twa[(twb+insertpoint)&twm]);
                        } else {
                            insertpoint ++;
                        }
                    } while (-- nchars > 0);
                    break;
                }

                // [ D - move cursor left
                case 'D': {
                    int nchars = EscSeqInt (escseqbuf, 1);
                    do {
                        if ((insertpoint > 0) && (twt[(twb+insertpoint-1)&twm] != '\n')) {
                            -- insertpoint;
                        }
                    } while (-- nchars > 0);
                    break;
                }

                // [ K - erase in line
                case 'K': {
                    int code = EscSeqInt (escseqbuf, 1);

                    // 0 or 2 : erase characters to end-of-line
                    //     delete chars starting with cursor up to next newline
                    //     but always leave the next newline intact
                    if ((code == 0) || (code == 2)) {
                        int i;
                        for (i = 0; i + insertpoint < theWholeUsed; i ++) {
                            if (twt[(twb+insertpoint+i)&twm] == '\n') break;
                        }
                        DeleteCharsAtInsertPoint (i);

                        // set attrs of preceding newline so ScreenTextView knows what
                        // background color to pad the remainder of this line with
                        for (i = insertpoint; -- i >= 0;) {
                            if (twt[(twb+i)&twm] == '\n') {
                                twa[(twb+i)&twm] = attrs;
                                break;
                            }
                        }
                    }

                    // 1 or 2 : erase characters up to and including cursor char
                    //     replace them with spaces of current attributes
                    if ((code == 1) || (code == 2)) {
                        int i = insertpoint;
                        if ((i >= 0) && (twt[(twb+i)&twm] != '\n')) i ++;
                        while (-- i >= 0) {
                            if (twt[(twb+i)&twm] == '\n') break;
                            twt[(twb+i)&twm] = ' ';
                            twa[(twb+i)&twm] = attrs;
                        }
                    }
                    break;
                }

                // [ m - select graphic rendition
                case 'm': {
                    for (int i = 0; escseqbuf[i] != 0;) {
                        char c;
                        int n = 0;
                        while (((c = escseqbuf[++i]) >= '0') && (c <= '7')) {
                            n = n * 8 + c - '0';
                        }
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
                            case 030:case 031:case 032:case 033:case 034:case 035:case 036:case 037: {
                                attrs &= ~TWA_FGCLR;
                                attrs |= (TWA_FGCLR & -TWA_FGCLR) * (n - 030);
                                break;
                            }
                            case 040:case 041:case 042:case 043:case 044:case 045:case 046:case 047: {
                                attrs &= ~TWA_BGCLR;
                                attrs |= (TWA_BGCLR & -TWA_BGCLR) * (n - 040);
                                break;
                            }
                        }
                    }
                    break;
                }

                default: {
                    Log.d (TAG, "unhandled escape seq '" + escseqstr + "'");
                    break;
                }
            }
        }
    }

    private static int EscSeqInt (char[] escseqbuf, int offset)
    {
        int n = 0;
        char c;
        while (((c = escseqbuf[offset++]) >= '0') && (c <= '9')) n = n * 10 + c - '0';
        return n;
    }

    /**
     * @brief Insert a printable character at the cursor position and increment cursor.
     * @param ch = character to store
     * @param at = corresponding attributes
     */
    private void InsertPrintableAtInsertPoint (char ch, short at)
    {
        // the only time we really do an insert is if we would overwrite a newline
        if ((insertpoint < theWholeUsed) && (twt[(twb+insertpoint)&twm] == '\n')) {

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

            // move everything starting with the newline down one spot to make room
            for (int i = ++ theWholeUsed; -- i > insertpoint;) {
                twt[(twb+i)&twm] = twt[(twb+i-1)&twm];
                twa[(twb+i)&twm] = twa[(twb+i-1)&twm];
            }

            // overwrite the newline with the new character and attributes
            twt[(twb+insertpoint)&twm] = ch;
            twa[(twb+insertpoint)&twm] = at;

            // advance cursor past new character
            insertpoint ++;
        } else {

            // overwrite existing printable with another printable
            // we might also be extending onto end of ring buffer
            OverwriteSomethingAtInsertPoint (ch, at);
        }
    }

    /**
     * @brief Overwrite the character at the insert point.
     *        But if at end of ring buffer, extend ring one char.
     * @param ch = character to store
     * @param at = corresponding attributes
     */
    private void OverwriteSomethingAtInsertPoint (char ch, short at)
    {
        int index  = (twb + insertpoint) & twm;
        twt[index] = ch;                    // store char at insert point
        twa[index] = at;                    // ...along with current attributes
        if (insertpoint < theWholeUsed) {   // if we are overwriting stuff on the existing line,
            insertpoint ++;                 //     (eg, it has been BS/CR'd), just inc index
        } else if (theWholeUsed < tws) {    // append, if array hasn't been filled yet,
            theWholeUsed = ++ insertpoint;  //     extend it by one character
        } else {                            // otherwise, we just overwrote the oldest char in ring,
            twb = (twb + 1) & twm;          //     increment index base
        }
    }

    /**
     * @brief Delete the given number of characters starting at the insert point.
     */
    private void DeleteCharsAtInsertPoint (int nch)
    {
        if (nch > 0) {
            theWholeUsed -= nch;
            int twu = theWholeUsed;
            for (int i = insertpoint; i < twu; i ++) {
                twt[(twb+i)&twm] = twt[(twb+i+nch)&twm];
                twa[(twb+i)&twm] = twa[(twb+i+nch)&twm];
            }
        }
    }

    public static int BGColor (int at)
    {
        return colorTable[(at&ScreenTextBuffer.TWA_BGCLR)/(ScreenTextBuffer.TWA_BGCLR&-ScreenTextBuffer.TWA_BGCLR)];
    }
    public static int FGColor (int at)
    {
        return colorTable[(at&ScreenTextBuffer.TWA_FGCLR)/(ScreenTextBuffer.TWA_FGCLR&-ScreenTextBuffer.TWA_FGCLR)];
    }
}
