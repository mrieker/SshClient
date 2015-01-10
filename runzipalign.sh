#!/bin/bash -v
if [ app/app-aligned.apk -ot app/app.apk ]
then
    rm -f app/app-aligned.apk
    ~/android-sdks/tools/zipalign -v 4 app/app.apk app/app-aligned.apk
fi
