/**
 * Handle messages queued by ScreenDataThread to be processed in the GUI thread.
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
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.TreeMap;

public class ScreenDataHandler extends Handler {
    public static final String TAG = "SshClient";

    public final static int OKDIAG     = 1;
    public final static int TEXTDIAG   = 2;
    public final static int YESNODIAG  = 3;
    public final static int CONNHIDE   = 4;
    public final static int INVALTEXT  = 5;
    public final static int SELKEYPAIR = 6;
    public final static int PANCOAST   = 7;

    private AlertDialog currentMenuDialog;
    private MySession session;
    private SshClient sshclient;

    public ScreenDataHandler (MySession ms)
    {
        session = ms;
        sshclient = ms.getSshClient ();
    }

    public void dispatchMessage (@NonNull Message msg)
    {
        HostNameText hostnametext = session.getHostnametext ();
        ScreenTextView screentextview = session.getScreentextview ();

        switch (msg.what) {

            // connection has been open for a few seconds,
            // hide the user@host:port box to save screen space
            case CONNHIDE: {
                if (hostnametext.GetState () == HostNameText.ST_ONLINE) {
                    hostnametext.SetState (HostNameText.ST_ONHIDE);
                }
                break;
            }

            // put up a dialog box with just an OK button
            case OKDIAG: {
                sshclient.ErrorAlert ("SSH Message", (String) msg.obj);
                break;
            }

            // prompt for passphrase or password
            //   args[0] = title
            //   args[1] = message
            //   args[2] = where OK/Cancel goes (null until answered)
            //   args[3] = if OK, where entered string goes (null if Cancel)
            //   args[4] = null: no 'save' checkmark
            //             else: string for 'save' checkmark
            //                   and returns Yes/No status (null if Cancel)
            //   args[5] = initial value
            case TEXTDIAG: {
                final String[] args = (String[]) msg.obj;

                // set up basic alert dialog box
                AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
                ab.setTitle (args[0]);
                ab.setMessage (args[1]);

                // we always want a text input field
                final EditText input = sshclient.MyEditText ();
                input.setSingleLine (true);
                input.setInputType (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                input.setText (args[5]);

                // maybe we want a save checkbox too
                CheckBox saveit = null;
                if (args[4] == null) {

                    // no just the text input field
                    ab.setView (input);
                } else {

                    // checkbox too, create checkbox
                    saveit = sshclient.MyCheckBox ();
                    saveit.setText (args[4]);

                    // the checkbox goes below the text input field
                    LinearLayout llv = new LinearLayout (sshclient);
                    llv.setOrientation (LinearLayout.VERTICAL);
                    llv.addView (input);
                    llv.addView (saveit);

                    // stuff all that on the dialog box
                    ab.setView (llv);
                }
                final CheckBox savecb = saveit;

                // set up the OK and Cancel buttons and their listeners
                ab.setPositiveButton ("OK", new DialogInterface.OnClickListener() {
                    public void onClick (DialogInterface dialog, int whichButton)
                    {
                        ClickedPasswordDialogOKButton (args, input, savecb);
                    }
                });
                ab.setNegativeButton ("Cancel", new DialogInterface.OnClickListener () {
                    public void onClick (DialogInterface dialog, int whichButton)
                    {
                        ClickedPasswordDialogCancelButton (args);
                    }
                });
                ab.setOnCancelListener (new DialogInterface.OnCancelListener () {
                    @Override
                    public void onCancel (DialogInterface dialogInterface)
                    {
                        ClickedPasswordDialogCancelButton (args);
                    }
                });

                // add listener so clicking DONE/NEXT on keyboard is same as clicking OK button
                input.setOnEditorActionListener (new TextView.OnEditorActionListener () {
                    @Override
                    public boolean onEditorAction (TextView textView, int actionId, KeyEvent keyEvent)
                    {
                        if ((actionId == EditorInfo.IME_ACTION_DONE) || (actionId == EditorInfo.IME_ACTION_NEXT)) {
                            currentMenuDialog.dismiss ();
                            ClickedPasswordDialogOKButton (args, input, savecb);
                            return true;
                        }
                        return false;
                    }
                });

                // display the dialog box
                currentMenuDialog = ab.show ();
                break;
            }

            // prompt yes/no
            case YESNODIAG: {
                final String[] args = (String[]) msg.obj;
                AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
                ab.setTitle ("SSH Yes/No Prompt");
                ab.setMessage (args[0]);
                ab.setPositiveButton ("Yes", new DialogInterface.OnClickListener () {
                    public void onClick (DialogInterface dialog, int whichButton) {
                        synchronized (args) {
                            args[1] = "Yes";
                            args.notify ();
                        }
                    }
                });
                ab.setNegativeButton ("No", new DialogInterface.OnClickListener () {
                    public void onClick (DialogInterface dialog, int whichButton) {
                        ClickedYesNoDialogNoButton (args);
                    }
                });
                ab.setOnCancelListener (new DialogInterface.OnCancelListener () {
                    @Override
                    public void onCancel (DialogInterface dialogInterface)
                    {
                        ClickedYesNoDialogNoButton (args);
                    }
                });
                ab.show ();
                break;
            }

            // time to flip the cursor, re-draw the visible text
            case INVALTEXT: {
                screentextview.InvalTextReceived ();
                break;
            }

            // select a keypair from the given list
            case SELKEYPAIR: {
                @SuppressWarnings("unchecked")
                final TreeMap<String,String> matches = (TreeMap<String,String>) msg.obj;

                // set up basic alert dialog box
                AlertDialog.Builder ab = new AlertDialog.Builder (sshclient);
                ab.setTitle ("Select KeyPair");

                // package up defined keypairs as each having a button in a scroll list
                // if/when a button is clicked, dismiss the menu then use that one for connecting
                LinearLayout llv = new LinearLayout (sshclient);
                llv.setOrientation (LinearLayout.VERTICAL);

                View.OnClickListener butlis = new View.OnClickListener () {
                    @Override
                    public void onClick (View view)
                    {
                        currentMenuDialog.dismiss ();
                        SelectedKeyPair (matches, ((Button) view).getText().toString ());
                    }
                };

                for (String ident : matches.keySet ()) {
                    Button but = sshclient.MyButton ();
                    but.setText (ident);
                    but.setOnClickListener (butlis);
                    llv.addView (but);
                }

                ScrollView sv = new ScrollView (sshclient);
                sv.addView (llv);
                ab.setView (sv);

                // None means don't use a keypair file
                ab.setPositiveButton ("None", new DialogInterface.OnClickListener () {
                    public void onClick (DialogInterface dialog, int whichButton)
                    {
                        SelectedKeyPair (matches, null);
                    }
                });

                // Cancel means don't bother connecting after all
                ab.setNegativeButton ("Cancel", new DialogInterface.OnClickListener () {
                    public void onClick (DialogInterface dialog, int whichButton)
                    {
                        SelectedKeyPair (matches, "<<cancel>>");
                    }
                });

                ab.setOnCancelListener (new DialogInterface.OnCancelListener () {
                    @Override
                    public void onCancel (DialogInterface dialogInterface)
                    {
                        SelectedKeyPair (matches, "<<cancel>>");
                    }
                });

                // display the dialog box
                currentMenuDialog = ab.show ();
                break;
            }

            // coasting for a recent pan
            case PANCOAST: {
                screentextview.PanCoastReceived ();
                break;
            }

            default: throw new RuntimeException ("bad what " + msg.what);
        }
    }

    /**
     * Someone just clicked the OK button on a passphrase/password dialog box
     * or pressed the Done/Next key on the keyboard.  So trigger the response.
     * @param args   = argument array that we use to signal completion on
     * @param input  = input box that contains entered passphrase/password
     * @param savecb = checkbox indicating whether or not user wants the value saved to disk
     */
    private static void ClickedPasswordDialogOKButton (String[] args, TextView input, CheckBox savecb)
    {
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (args) {
            args[2] = "OK";
            args[3] = input.getText ().toString ();
            args[4] = (savecb == null) ? null : savecb.isChecked () ? "Yes" : "No";
            args.notify ();
        }
    }

    /**
     * Someone just clicked the Cancel button on a passphrase/password dialog box,
     * or clicked the Back button to cancel it.
     * @param args = argument array that we use to signal completion on
     */
    private static void ClickedPasswordDialogCancelButton (String[] args)
    {
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (args) {
            args[2] = "Cancel";
            args[3] = null;
            args[4] = null;
            args.notify ();
        }
    }

    private static void ClickedYesNoDialogNoButton (String[] args)
    {
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (args) {
            args[1] = "No";
            args.notify ();
        }
    }

    private static void SelectedKeyPair (TreeMap<String,String> matches, String selection)
    {
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (matches) {
            matches.put ("<<answer>>", selection);
            matches.notifyAll ();
        }
    }
}
