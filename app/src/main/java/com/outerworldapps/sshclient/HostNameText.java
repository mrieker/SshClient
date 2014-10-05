/**
 * username@hostname[:portnumber] entry box for the connection we are about to make.
 * Uses saved logins file for autocomplete suggestions.
 * Initiates the connection when the Done/Next key is clicked.
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
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.InputType;
import android.text.method.KeyListener;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;

public class HostNameText extends AutoCompleteTextView
        implements TextView.OnEditorActionListener, View.OnFocusChangeListener {

    public static final int HIDE_TOPBAR_MS = 5000;

    public final static int ST_ENTER  = 0;  // entering value
    public final static int ST_CONN   = 1;  // attempting to connect
    public final static int ST_ONLINE = 2;  // connected and online, with this view visible
    public final static int ST_ONHIDE = 3;  // connected and online, but this view hidden
    public final static int ST_DISCO  = 4;  // disconnected

    private int state = ST_ENTER;
    private KeyListener normalKeyListener;
    private long hideUptimeMillis = Long.MAX_VALUE;
    private MySession session;
    private SshClient sshclient;

    public HostNameText (MySession ms)
    {
        super (ms.getSshClient ());

        session   = ms;
        sshclient = ms.getSshClient ();

        setTextSize (SshClient.UNIFORM_TEXT_SIZE);
        setTypeface (Typeface.MONOSPACE);
        setSingleLine (true);
        setThreshold (0);  // still requires user to enter one char
        setHorizontallyScrolling (true);
        setOnEditorActionListener (this);
        setOnFocusChangeListener (this);
        setHint ("user@host[:port]");
        normalKeyListener = getKeyListener ();
        setAdapter (sshclient.getSavedlogins ().GetAutoCompleteAdapter ());
        SetState (ST_ENTER);
    }

    /**
     * Use text color to indicate connection status.
     * Also allow/block modifications as appropriate.
     *   BLACK : waiting for input of username@hostname[:portnumber]
     *    BLUE : attempting to connect
     *   GREEN : connected and online
     *     RED : disconnected
     */
    public void SetState (int st)
    {
        switch (st) {
            case ST_ENTER: {
                hideUptimeMillis = Long.MAX_VALUE;
                setVisibility (View.VISIBLE);
                setKeyListener (normalKeyListener);
                setInputType (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                setBackgroundColor (Color.WHITE);
                setTextColor (Color.BLACK);
                requestFocus ();
                break;
            }
            case ST_CONN: {
                hideUptimeMillis = Long.MAX_VALUE;
                setVisibility (View.VISIBLE);
                setKeyListener (null);
                setBackgroundColor (Color.BLACK);
                setTextColor (Color.CYAN);
                break;
            }
            case ST_ONLINE: {
                setVisibility (View.VISIBLE);
                setKeyListener (null);
                setBackgroundColor (Color.BLACK);
                setTextColor (Color.GREEN);
                if (HIDE_TOPBAR_MS > 0) {
                    long oldQueued = hideUptimeMillis;
                    hideUptimeMillis = SystemClock.uptimeMillis () + HIDE_TOPBAR_MS;
                    if (hideUptimeMillis < oldQueued) {
                        session.getScreendatahandler ().sendEmptyMessageAtTime (ScreenDataHandler.CONNHIDE, hideUptimeMillis);
                    }
                }
                break;
            }
            case ST_ONHIDE: {
                if (hideUptimeMillis <= SystemClock.uptimeMillis ()) {
                    setVisibility (View.GONE);
                    setKeyListener (null);
                    hideUptimeMillis = Long.MAX_VALUE;
                } else if (hideUptimeMillis < Long.MAX_VALUE) {
                    session.getScreendatahandler ().sendEmptyMessageAtTime (ScreenDataHandler.CONNHIDE, hideUptimeMillis);
                }
                break;
            }
            case ST_DISCO: {
                hideUptimeMillis = Long.MAX_VALUE;
                setVisibility (View.VISIBLE);
                setKeyListener (normalKeyListener);
                setInputType (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                setBackgroundColor (Color.WHITE);
                setTextColor (Color.RED);

                // switch focus to screentextview so user has to click on us for focus.
                // otherwise seems we don't get our onFocusChange() called and we get
                // stuck in ST_DISCO state.
                session.getScreentextview ().requestFocus ();
                break;
            }
            default: throw new RuntimeException ("bad state " + st);
        }
        state = st;
    }
    public int GetState ()
    {
        return state;
    }

    /**
     * Whenever field gets focus while in disconnected state, switch to enter state.
     */
    @Override // View.OnFocusChangeListener
    public void onFocusChange (View v, boolean hasFocus)
    {
        if (hasFocus && (state == ST_DISCO)) SetState (ST_ENTER);
    }

    /**
     * Whenever user clicks Done/Next key in hostname box,
     * start a connection to the given host.
     */
    @Override // TextView.OnEditorActionListener
    public boolean onEditorAction (TextView v, int actionId, KeyEvent ke)
    {
        if ((actionId == EditorInfo.IME_ACTION_DONE) || (actionId == EditorInfo.IME_ACTION_NEXT)) {
            session.StartConnecting ();
            return true;
        }
        return false;
    }
}