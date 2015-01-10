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
import android.text.Editable;
import android.text.InputType;
import android.text.method.KeyListener;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class HostNameText extends LinearLayout
        implements View.OnClickListener, TextView.OnEditorActionListener, View.OnFocusChangeListener {

    public static final int HIDE_TOPBAR_MS = 5000;

    public final static int ST_ENTER  = 0;  // entering value
    public final static int ST_CONN   = 1;  // attempting to connect
    public final static int ST_ONLINE = 2;  // connected and online, with this view visible
    public final static int ST_ONHIDE = 3;  // connected and online, but this view hidden
    public final static int ST_DISCO  = 4;  // disconnected

    private AutoCompleteTextView actv;
    private Button okbut;
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

        okbut = new Button (ms.getSshClient ());
        okbut.setText ("OK");
        okbut.setEnabled (false);
        okbut.setOnClickListener (this);
        okbut.setLayoutParams (new LayoutParams (ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        actv = new AutoCompleteTextView (ms.getSshClient ());
        actv.setTextSize (SshClient.UNIFORM_TEXT_SIZE);
        actv.setTypeface (Typeface.MONOSPACE);
        actv.setSingleLine (true);
        actv.setThreshold (0);  // still requires user to enter one char
        actv.setHorizontallyScrolling (true);
        actv.setOnEditorActionListener (this);
        actv.setOnFocusChangeListener (this);
        actv.setHint ("user@host[:port]");
        actv.setAdapter (sshclient.getSavedlogins ().GetAutoCompleteAdapter ());
        actv.setLayoutParams (new LayoutParams (ViewGroup.LayoutParams.FILL_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        normalKeyListener = actv.getKeyListener ();

        SetState (ST_ENTER);

        setOrientation (HORIZONTAL);
        addView (okbut);
        addView (actv);
    }

    public void setText (String text)
    {
        actv.setText (text);
    }
    public Editable getText ()
    {
        return actv.getText ();
    }
    public void setAdapter (ArrayAdapter<String> adapter)
    {
        actv.setAdapter (adapter);
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
                actv.setKeyListener (normalKeyListener);
                actv.setInputType (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                actv.setBackgroundColor (Color.WHITE);
                actv.setTextColor (Color.BLACK);
                actv.requestFocus ();
                okbut.setEnabled (true);
                break;
            }
            case ST_CONN: {
                hideUptimeMillis = Long.MAX_VALUE;
                setVisibility (View.VISIBLE);
                actv.setKeyListener (null);
                actv.setBackgroundColor (Color.BLACK);
                actv.setTextColor (Color.CYAN);
                okbut.setEnabled (false);
                break;
            }
            case ST_ONLINE: {
                setVisibility (View.VISIBLE);
                actv.setKeyListener (null);
                actv.setBackgroundColor (Color.BLACK);
                actv.setTextColor (Color.GREEN);
                okbut.setEnabled (false);
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
                okbut.setEnabled (false);
                if (hideUptimeMillis <= SystemClock.uptimeMillis ()) {
                    setVisibility (View.GONE);
                    actv.setKeyListener (null);
                    hideUptimeMillis = Long.MAX_VALUE;
                } else if (hideUptimeMillis < Long.MAX_VALUE) {
                    session.getScreendatahandler ().sendEmptyMessageAtTime (ScreenDataHandler.CONNHIDE, hideUptimeMillis);
                }
                break;
            }
            case ST_DISCO: {
                hideUptimeMillis = Long.MAX_VALUE;
                setVisibility (View.VISIBLE);
                actv.setKeyListener (normalKeyListener);
                actv.setInputType (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                actv.setBackgroundColor (Color.WHITE);
                actv.setTextColor (Color.RED);
                okbut.setEnabled (false);

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

    /**
     * The OK button was clicked
     */
    @Override // OnClickListener
    public void onClick (View v)
    {
        session.StartConnecting ();
    }
}