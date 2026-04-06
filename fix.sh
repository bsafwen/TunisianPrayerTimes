#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

find android-app/app/src/main/res/layout -type f -name "*.xml" -exec sed -i '' 's/android:layoutDirection="rtl"/android:layoutDirection="ltr"/g' {} +
