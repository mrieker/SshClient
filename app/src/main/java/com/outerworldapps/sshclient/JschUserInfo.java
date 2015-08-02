/**
 * Used by ScreenDataThread to notify the user of various things.
 * It is a bunch of callbacks to prompt the user for things,
 * invoked by the JSch library from the ScreenDataThread.
 */
// http://epaul.github.io/jsch-documentation/javadoc/com/jcraft/jsch/UserInfo.html

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


import com.jcraft.jsch.UserInfo;

public class JschUserInfo implements UserInfo {
    public boolean savePassword;  // user wants password saved in database (if connection succeeds)
    public String dbpassword;     // password that was in database before connection started (or null)
    public String password;       // password entered by prompt

    private MySession session;
    private String passphrase;    // passphrase entered by prompt

    public JschUserInfo (MySession ms)
    {
        session = ms;
    }

    // Returns the passphrase entered by the user.
    public String getPassphrase () { return passphrase; }

    // Returns the password entered by the user.
    public String getPassword ()   { return password;   }

    // Prompts the user for the passphrase to the private key.
    public boolean promptPassphrase (String message)
    {
        session.ScreenMsg ("...prompting for passphrase\r\n");
        String[] ret = DialogStringPrompt ("SSH Passphrase Prompt", message, null, "");
        passphrase = ret[0];
        return passphrase != null;
    }

    // Prompts the user for a password used for authentication for the remote server.
    // We pass the password from the database if any as the default value.
    public boolean promptPassword (String message)
    {
        if (dbpassword != null) {
            session.ScreenMsg ("...trying saved password\r\n");
            password     = dbpassword;  // password from the database
            savePassword = true;        // save it back to database if it is still good
            dbpassword   = null;        // only try it once
        } else {
            session.ScreenMsg ("...prompting for password\r\n");
            String[] ret = DialogStringPrompt ("SSH Password Prompt", message, "Save password", "");
            password     = ret[0];
            savePassword = (ret[1] != null) && ((ret[1].charAt (0) & 1) != 0);
        }
        return password != null;
    }

    // Prompts the user to answer a yes/no question, eg,
    // is this the correct fingerprint for that host?
    public boolean promptYesNo (String message)
    {
        String[] args = { message, null };
        session.getScreendatahandler ().obtainMessage (ScreenDataHandler.YESNODIAG, args).sendToTarget ();
        String answer;
        synchronized (args) {
            while ((answer = args[1]) == null) {
                try {
                    args.wait ();
                } catch (InterruptedException ie) {
                }
            }
        }
        return (answer.charAt (0) & 1) != 0;
    }

    // Shows an informational message to the user.
    public void showMessage (String message)
    {
        session.getScreendatahandler ().obtainMessage (ScreenDataHandler.OKDIAG, message).sendToTarget ();
    }

    /**
     * Prompt user for a string and return the reply (null if clicks Cancel).
     * @param title = title for the dialog box
     * @param message = message for the dialog box
     * @param saveprompt = null: don't give the user a 'save to disk' checkbox
     *                     else: message for the save to disk checkbox
     * @param initial = initial value string
     * returns String[0] = response (or null if Cancel)
     *               [1] = null, "Yes" or "No" for the 'save to disk' checkbox
     */
    private String[] DialogStringPrompt (String title, String message, String saveprompt, String initial)
    {
        String[] args = { title, message, null, null, saveprompt, initial };
        session.getScreendatahandler ().obtainMessage (ScreenDataHandler.TEXTDIAG, args).sendToTarget ();
        String answer, saveit;
        synchronized (args) {
            while (args[2] == null) {
                try {
                    args.wait ();
                } catch (InterruptedException ie) {
                }
            }
            answer = args[3];
            saveit = args[4];
        }
        return new String[] { answer, saveit };
    }
}
