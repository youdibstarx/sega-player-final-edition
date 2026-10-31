package com.segaiptv.player

import androidx.compose.runtime.Composable

private val viewerStrings = mapOf(
    "continue" to listOf("Continue watching", "Continuer à regarder", "Seguir viendo", "متابعة المشاهدة"),
    "recent" to listOf("Recent channels", "Chaînes récentes", "Canales recientes", "آخر القنوات"),
    "resume" to listOf("Resume", "Reprendre", "Continuar", "متابعة"),
    "start_over" to listOf("Start over", "Recommencer", "Desde el inicio", "من البداية"),
    "resume_prompt" to listOf("Resume from saved position?", "Reprendre la lecture ?", "¿Continuar la reproducción?", "متابعة من آخر نقطة؟"),
    "next_episode" to listOf("Next episode", "Épisode suivant", "Siguiente episodio", "الحلقة التالية"),
    "tracks" to listOf("Audio / subtitles", "Audio / sous-titres", "Audio / subtítulos", "الصوت والترجمة"),
    "audio" to listOf("Audio", "Audio", "Audio", "الصوت"),
    "subtitles" to listOf("Subtitles", "Sous-titres", "Subtítulos", "الترجمة"),
    "automatic" to listOf("Automatic", "Automatique", "Automático", "تلقائي"),
    "off" to listOf("Off", "Désactivé", "Desactivado", "إيقاف"),
    "no_tracks" to listOf("No tracks supplied", "Aucune piste disponible", "No hay pistas disponibles", "لا توجد مسارات متاحة"),
    "reconnecting" to listOf("Reconnecting (up to 3 attempts)…", "Reconnexion (3 essais maximum)…", "Reconectando (hasta 3 intentos)…", "إعادة الاتصال (حتى 3 محاولات)…"),
    "viewer_settings" to listOf("Viewing options", "Options de lecture", "Opciones de visualización", "خيارات المشاهدة"),
    "ui_size" to listOf("Interface size", "Taille de l’interface", "Tamaño de interfaz", "حجم الواجهة"),
    "compact" to listOf("Compact", "Compacte", "Compacta", "مضغوط"),
    "normal" to listOf("Normal", "Normale", "Normal", "عادي"),
    "large" to listOf("Large", "Grande", "Grande", "كبير"),
    "parental" to listOf("Parental control", "Contrôle parental", "Control parental", "القفل الأبوي"),
    "parental_hint" to listOf("Create a PIN, then choose bouquets here or lock the current item in the player. Locks return when the app goes to the background.", "Créez un PIN puis choisissez les bouquets ici ou verrouillez le contenu dans le lecteur. Verrouillage au passage en arrière-plan.", "Crea un PIN y elige categorías aquí o bloquea el contenido en el reproductor. Se bloquea al pasar a segundo plano.", "أنشئ رمزاً ثم اختر الباقات هنا أو اقفل المحتوى من المشغّل. يعود القفل عند انتقال التطبيق للخلفية."),
    "set_pin" to listOf("Create PIN", "Créer un PIN", "Crear PIN", "إنشاء رمز"),
    "enter_pin" to listOf("Enter PIN", "Saisir le PIN", "Introducir PIN", "أدخل الرمز"),
    "confirm_pin" to listOf("Repeat PIN", "Répéter le PIN", "Repetir PIN", "تأكيد الرمز"),
    "pin_hint" to listOf("4–8 digits. Keep your PIN; it is stored only on this device.", "4 à 8 chiffres. Conservez le PIN ; il reste sur cet appareil.", "De 4 a 8 dígitos. Guarda el PIN; solo se almacena en este dispositivo.", "من 4 إلى 8 أرقام. احتفظ بالرمز؛ يُحفظ على هذا الجهاز فقط."),
    "pin_error" to listOf("Invalid PIN or too many attempts. After 5 failures, wait 30 seconds.", "PIN incorrect ou trop de tentatives. Après 5 échecs, attendez 30 secondes.", "PIN incorrecto o demasiados intentos. Tras 5 fallos, espera 30 segundos.", "رمز غير صحيح أو محاولات كثيرة. بعد 5 محاولات انتظر 30 ثانية."),
    "confirm" to listOf("Confirm", "Confirmer", "Confirmar", "تأكيد"),
    "unlock" to listOf("Unlock with PIN", "Déverrouiller avec PIN", "Desbloquear con PIN", "فتح بالرمز"),
    "lock_bouquets" to listOf("Choose a bouquet to lock", "Choisir un bouquet à verrouiller", "Elegir categoría para bloquear", "اختر باقة لقفلها"),
    "lock_item" to listOf("Lock this item", "Verrouiller ce contenu", "Bloquear este contenido", "قفل هذا المحتوى"),
    "lock_group" to listOf("Lock this bouquet", "Verrouiller ce bouquet", "Bloquear esta categoría", "قفل هذه الباقة"),
    "remove_locks" to listOf("Disable parental control", "Désactiver le contrôle parental", "Desactivar control parental", "إلغاء القفل الأبوي"),
    "clear_history" to listOf("Clear viewing history", "Effacer l’historique", "Borrar historial", "مسح سجل المشاهدة"),
    "empty_history" to listOf("No viewing history yet", "Aucun historique", "Sin historial", "لا يوجد سجل بعد"),
    "diagnostics" to listOf("Diagnostics", "Diagnostic", "Diagnóstico", "تشخيص"),
    "network_ok" to listOf("Internet connection validated", "Connexion Internet validée", "Conexión a Internet validada", "تم التحقق من اتصال الإنترنت"),
    "network_check" to listOf("Internet not validated; check your connection", "Internet non validé ; vérifiez la connexion", "Internet no validado; revisa la conexión", "لم يتم التحقق من الإنترنت؛ راجع الاتصال"),
    "catalogue_loading" to listOf("Refreshing catalogue…", "Actualisation du catalogue…", "Actualizando catálogo…", "تحديث القوائم…"),
    "catalogue_ok" to listOf("Catalogue available", "Catalogue disponible", "Catálogo disponible", "القوائم متاحة"),
    "catalogue_empty" to listOf("No catalogue loaded", "Aucun catalogue chargé", "No hay catálogo cargado", "لم تُحمّل قوائم"),
    "source_problem" to listOf("A source or server reported an error. See Playlists.", "Erreur de source ou serveur. Voir Listes.", "Error de fuente o servidor. Consulta Listas.", "خطأ في المصدر أو الخادم. راجع القوائم."),
    "source_ok" to listOf("No source errors reported", "Aucune erreur de source signalée", "Sin errores de fuente registrados", "لا توجد أخطاء مصادر مسجلة"),
    "player_status" to listOf("Last playback error code", "Dernier code d’erreur de lecture", "Último código de error", "رمز آخر خطأ تشغيل"),
    "epg_next" to listOf("Next", "À suivre", "Después", "التالي"),
    "cached" to listOf("Saved catalogue shown; refresh failed.", "Catalogue enregistré affiché ; actualisation échouée.", "Catálogo guardado; la actualización falló.", "تُعرض القوائم المحفوظة؛ تعذّر التحديث.")
)

@Composable
internal fun fx(key: String): String {
    val index = when (LocalAppLanguage.current.code) { "fr" -> 1; "es" -> 2; "ar" -> 3; else -> 0 }
    return viewerStrings.getValue(key)[index]
}
