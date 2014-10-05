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


import android.util.Log;

public class ScreenTextBuffer {
    public static final String TAG = "SshClient";

    private final static char[] eightspaces = { ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ' };

    public char[] twt;              // theWholeText array ring buffer
    public int twb;                 // base of all other indices
    public int twm;                 // twt.length - 1 (always power-of-two - 1)
    public int tws;                 // twt.length (always power-of-two)

    public boolean needToRender;    // next onDraw() needs to create theWholeText->visibleLine{Beg,End}s{} mapping
    public int renderCursor;        // next onDraw() needs to adjust scrolling to make this cursor visible

    private boolean frozen;         // indicates frozen mode
    public  int insertpoint;        // where next incoming text gets inserted in theWholeText
    public  int scrolledCharsLeft;  // number of chars we are shifted left
    public  int scrolledLinesDown;  // number of lines we are shifted down
    public  int theWholeUsed;       // amount in theWholeText that is occupied starting at twb
    private Runnable notify;        // what to notify when there are changes

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
                char[] newtwt = new char[newtws];

                int newtwm = newtws - 1;                   // bitmask for wrapping indices
                int newtwu = 0;                            // no array elements used yet
                int newtwb = 0;                            // start of used elements is beginning of array
                for (int i = 0; i < theWholeUsed; i ++) {  // step through each used char in old array
                    newtwt[(newtwb+newtwu)&newtwm] = twt[(twb+i)&twm];
                    if (newtwu < newtws) newtwu ++;        // if not yet full, it has one more char now
                    else newtwb = (newtwb + 1) & newtwm;   // otherwise, old char on front was overwritten
                }

                insertpoint += newtwu - theWholeUsed;
                if (insertpoint < 0) insertpoint = 0;

                twt = newtwt;
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
            if ((int)ch < 32) {

                // process the control character
                switch ((int)ch) {

                    // bell
                    case 7: {
                        SshClient.MakeBeepSound ();
                        continue;
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
                        continue;
                    }

                    // tab: space to next 8-character position in line
                    case 9: {
                        int k;
                        for (k = insertpoint; k > 0; -- k) {
                            if (twt[(twb+k-1)&twm] == '\n') break;
                        }
                        k = (insertpoint - k) % 8;
                        IncomingWork (eightspaces, 0, 8 - k);
                        continue;
                    }

                    // newline (linefeed): advance insertion point to end of text and append newline
                    // also reset horizontal scrolling in case a prompt is incoming next
                    // has to work as part of CR/LF sequence
                    case 10: {
                        insertpoint = theWholeUsed;
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
                        continue;
                    }

                    // who knows - ignore all other control characters
                    default: {
                        Log.d (TAG, "ignoring incoming " + (int) ch);
                        continue;
                    }
                }
            }

            twt[(twb+insertpoint)&twm] = ch;    // store char at insert point
            if (insertpoint < theWholeUsed) {   // if we are overwriting stuff on the existing line,
                insertpoint ++;                 //     (ie it has been BS/CR'd), just inc index
            } else if (theWholeUsed < tws) {    // append, if array hasn't been filled yet,
                theWholeUsed = ++ insertpoint;  //     extend it by one character
            } else {                            // otherwise, we just overwrote the oldest char in ring,
                twb = (twb + 1) & twm;          //     increment index base
            }
        }
    }
}
