#! /usr/bin/env bash

set -e

wget -O chrome-linux.zip "https://www.googleapis.com/download/storage/v1/b/chromium-browser-snapshots/o/Linux_x64%2F827102%2Fchrome-linux.zip?generation=1605233458736188&alt=media"
wget -O chromedriver.zip "https://www.googleapis.com/download/storage/v1/b/chromium-browser-snapshots/o/Linux_x64%2F827102%2Fchromedriver_linux64.zip?generation=1605233463367665&alt=media"

unzip chrome-linux.zip
unzip chromedriver.zip
