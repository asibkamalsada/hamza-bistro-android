package de.hamzabistro.printstation.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import de.hamzabistro.printstation.core.ApkInstaller
import java.io.File

/**
 * Android's side of "Installieren": the APK the app fetched, handed to the
 * system's installer through the FileProvider — no browser in between, whose
 * own download prompt it would depend on. Android asks the person before it
 * installs, the first time also whether this app may install at all, and
 * takes the APK only when it is signed with the key of the app it replaces.
 */
class AndroidApkInstaller(private val context: Context) : ApkInstaller {
    /**
     * Only a build with this app's own id: anything else would become a
     * second app beside this one, which "Installieren" never is. So a debug
     * build, whose id is not the release's, leaves the release to the browser.
     */
    override fun isThisApp(apk: File): Boolean = archive(apk)?.packageName == context.packageName

    override fun open(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** What the file says it is; null when it is not an APK at all. */
    private fun archive(apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.path, 0)
        }

    companion object {
        /** Where the fetched APK lies, under the cache: res/xml/file_paths.xml hands out this folder. */
        const val DIR = "updates"

        private const val APK_TYPE = "application/vnd.android.package-archive"
    }
}
