package com.tunisianprayertimes

import android.util.AtomicFile
import android.util.Log
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Windows File.renameTo cannot replace an existing file as Android's rename can. */
@Implements(AtomicFile::class)
class AndroidAtomicFileShadow {
    companion object {
        @JvmStatic @Implementation
        fun rename(source: File, target: File) {
            if (File.separatorChar == '\\' && source.isFile && target.isFile) {
                try {
                    Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (error: IOException) {
                    Log.e("AtomicFile", "Failed to rename $source to $target", error)
                }
            } else {
                Shadow.directlyOn<Void?, AtomicFile>(AtomicFile::class.java, "rename",
                    ClassParameter.from(File::class.java, source), ClassParameter.from(File::class.java, target))
            }
        }
    }
}
