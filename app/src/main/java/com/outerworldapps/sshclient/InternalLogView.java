/**
 * Show list of network interfaces and their IP address(es).
 */

//    Copyright (C) 2015, Mike Rieker, Beverly, MA USA
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
import android.graphics.Typeface;
import android.widget.ScrollView;
import android.widget.TextView;

@SuppressLint("ViewConstructor")
public class InternalLogView extends ScrollView {

    private SshClient sshClient;
    public  TextView  textView;

    public InternalLogView (SshClient ctx)
    {
        super (ctx);
        sshClient = ctx;
        textView = new TextView (ctx);
        textView.setTextSize (SshClient.UNIFORM_TEXT_SIZE);
        textView.setTypeface (Typeface.MONOSPACE);
        addView (textView);
    }

    public void show ()
    {
        sshClient.setContentView (this);

        // set up method to be called if this screen is back-buttoned to
        sshClient.pushBackAction (new SshClient.BackAction () {
            @Override
            public boolean okToPop ()
            {
                // it is always ok to back-button away from this page
                return true;
            }

            @Override
            public void reshow ()
            {
                show ();
            }

            @Override
            public String name ()
            {
                return "internallog";
            }

            @Override
            public MySession session ()
            {
                return null;
            }
        });
    }
}
