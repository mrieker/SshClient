//    Copyright (C) 2020, Mike Rieker, Beverly, MA USA
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

import java.util.HashMap;

// directory that we assume is readable cuz it is one of the things like getFilesDir()
// or is a parent of one of those directories all the way through root
public class KnownReadable {
    public String name;     // does not contain '/'
    public HashMap<String,KnownReadable> childs = new HashMap<> ();

    public void log (String indent)
    {
        Log.d ("SshClient", indent + name + "/");
        indent += "  ";
        for (KnownReadable child : childs.values ()) {
            child.log (indent);
        }
    }
}
