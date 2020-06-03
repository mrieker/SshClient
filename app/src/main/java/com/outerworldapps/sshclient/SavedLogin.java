/**
 * Holds data for a single saved username@hostname[:portnumber]
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


public class SavedLogin {
    private String userathost;  // username@hostname[:portnumber]
    private String datavalues;  // "" or password=<password>

    // get values from the supplied database record
    public SavedLogin (String rec)
            throws Exception
    {
        int i = rec.indexOf (' ');
        if (i < 0) throw new Exception ("missing space");
        userathost = rec.substring (0, i);
        datavalues = rec.substring (++ i);
    }

    // gather values from the various forms
    // - uah = username@hostname[:portnumber] string
    // - kpi = keypair identifier (or null if none)
    // - pwd = password (or null to not save)
    public SavedLogin (String uah, String kpi, String pwd)
    {
        userathost = uah;
        datavalues = "";
        if (kpi != null) {
            datavalues += "&keypair=" + DataEncode (kpi);
        }
        if (pwd != null) {
            datavalues += "&password=" + DataEncode (pwd);
        }
        if (!datavalues.equals ("")) datavalues = datavalues.substring (1);
    }

    // get username@hostname[:portnumber] string
    public String getUserAtHost () { return userathost; }

    // get record contents to save to database
    public String getRecord () { return userathost + " " + datavalues; }

    // get data summary to show in menus so user can tell what is stored along with name
    public String getSummary ()
    {
        StringBuilder sb = new StringBuilder ();
        String kp = getKeypair ();
        if (kp != null) {
            if (!sb.toString ().equals ("")) sb.append (", ");
            sb.append ("kp=");
            sb.append (kp);
        }
        String pw = getPassword ();
        if (pw != null) {
            if (!sb.toString ().equals ("")) sb.append (", ");
            sb.append ("pw");
        }
        String st = sb.toString ();
        if (!st.equals ("")) {
            st = "\n" + st;
        }
        return st;
    }

    // retrieve keypair ident, null if not stored
    public String getKeypair ()
    {
        return getValue ("keypair");
    }

    // retrieve password, null if not stored
    public String getPassword ()
    {
        return getValue ("password");
    }

    private String getValue (String key)
    {
        int i = datavalues.indexOf (key + "=");
        if (i < 0) return null;
        i += key.length () + 1;
        int j = datavalues.indexOf ('&', i);
        if (j < 0) j = datavalues.length ();
        return DataDecode (datavalues.substring (i, j));
    }

    /**
     * Similar to urlencode/decode.
     */
    private final static String baddatachars = " &=+%;\n";
    private static String DataEncode (String s)
    {
        StringBuilder sb = new StringBuilder ();
        for (char c : s.toCharArray ()) {
            if (baddatachars.indexOf (c) >= 0) {
                sb.append ('%');
                sb.append (Integer.toString (c));
                sb.append (';');
            } else {
                sb.append (c);
            }
        }
        return sb.toString ();
    }
    private static String DataDecode (String s)
    {
        StringBuilder sb = new StringBuilder ();
        int i, j;
        for (i = j = 0; (i = s.indexOf ('%', i)) >= 0; i = ++ j) {
            sb.append (s, j, i);
            j = s.indexOf (';', ++ i);
            char c = (char)Integer.parseInt (s.substring (i, j));
            sb.append (c);
        }
        sb.append (s.substring (j));
        return sb.toString ();
    }
}
