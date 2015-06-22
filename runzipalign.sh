#!/bin/bash -v
#
#  1) Click 'Build Variants' on lower left edge of screen
#     - then select 'release' under 'Build Variant'
#  2) Click 'Build' -> 'Rebuild Project'
#  3) Click 'Build' -> 'Generate Signed APK'
#  4) Run this script
#
cd `dirname $0`
if [ app/app-aligned.apk -ot app/app.apk ]
then
    rm -f app/app-aligned.apk
    ~/android-sdks/build-tools/19.1.0/zipalign -v 4 app/app.apk app/app-aligned.apk
fi
