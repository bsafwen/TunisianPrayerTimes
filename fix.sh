find android-app/app/src/main/res/layout -type f -name "*.xml" -exec sed -i '' 's/android:layoutDirection="rtl"/android:layoutDirection="ltr"/g' {} +
