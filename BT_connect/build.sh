#!/bin/bash

# Build APK
bash gradlew assembleRelease || { echo "Build gagal"; exit 1; }

# Path APK di local (PC/laptop)
file="app/build/outputs/apk/release/app-release.apk"

# Cek apakah file APK hasil build ada
if [ ! -f "$file" ]; then
    echo "APK tidak ditemukan: $file"
    exit 1
fi

#cek signer
echo "cek signer apk"
apksigner verify --print-certs "$file" 
echo " "
echo " "




# Push APK ke Android
echo "Mengirim APK ke /data/local/tmp/"
if adb push "$file" /data/local/tmp/app-release.apk; then
    echo "Push berhasil, lanjut menginstall APK..."

    # Install APK
    adb shell pm install -r /data/local/tmp/app-release.apk
    status=$?

    # Hapus APK jika install berhasil
    if [ $status -eq 0 ]; then
        echo "Install sukses, menghapus file APK dari perangkat"
        adb shell rm /data/local/tmp/app-release.apk
    else
        adb shell rm /data/local/tmp/app-release.apk
        echo "Install gagal"
    fi
else
    echo "Gagal push APK"
    exit 1
fi