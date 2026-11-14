# SEGA PLAYER 0.5.29 — تثبيت التحديث على Android 7

الأساس PR #14 عند 0c5031f3229e760a20051718c65227f9411a39df؛ لا يُستخدم Android القديم في main.

المشكلة المثبتة في الكود: بعد تنزيل APK وفحص SHA-256 والشهادة والحزمة والإصدار، كان التطبيق يستدعي java.nio.file.Files.move وFile.toPath. هذه APIs تتطلب API26 أو desugar_jdk_libs_nio؛ المشروع يستعمل desugar_jdk_libs الافتراضي. حراسة canRequestPackageInstalls وحدها في 0.5.28 لا تعالج هذه الخطوة اللاحقة.

استُبدلت الخطوة بإعادة تسمية الملف المؤقت داخل المجلد نفسه بواسطة java.io.File.renameTo، المتاحة على Android 7. عند فشل النقل يُرفع استثناء قابل للمعالجة مع بقاء APK السابق؛ لا تثبيت قبل التحقق ولا تغيير لفحوص الشهادة أو التجزئة. لا اعتماد جديد ولا رفع minSdk ولا تعديل مفاتيح.

اختبارا استبدال APK السابق وفشل النقل مع حفظ السابق يعملان على Robolectric API24 وAPI35. هذه اختبارات نظام ملفات/تراجع، ولا تثبت واجهة Android Installer أو صوت HDMI على جهاز فعلي. النسخة code47/name0.5.29؛ التوقيع يتم عبر workflow الحالي دون قراءة المفاتيح محلياً.

المراجع الرسمية: [تنويعات desugaring](https://android-developers.googleblog.com/2023/02/api-desugaring-supporting-android-13-and-java-nio.html)، [جدول nio](https://developer.android.com/studio/write/java11-nio-support-table)، [File.renameTo](https://developer.android.com/reference/java/io/File#renameTo(java.io.File)).

قبول الجهاز: تثبيت النسخة الموقعة كتحديث دون مسح البيانات، تجربة تنزيل تحديث موثوق على Android 7 وفتح مثبت النظام، ثم مصفوفة الصوت والصور والريموت في تقرير المراجعة. لا يوضع latest.json في الإنتاج قبل قبول الأجهزة وإذن النشر.

هذا الفرع يتضمن بانل Android التاريخي، ولا يحل محل بانل PR #15 و#16. الدمج والنشر غير منفذين.
