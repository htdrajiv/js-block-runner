package rn.jsblockrunner

import com.intellij.openapi.project.Project
import java.io.File

object NodeLocator {

    fun findNode(project: Project): String? {
        val candidates = listOf("node", "node.exe")
        
        // 1. Check system PATH first
        val pathEnv = System.getenv("PATH")
        if (pathEnv != null) {
            val dirs = pathEnv.split(File.pathSeparator).map { it.trim() }.filter { it.isNotBlank() }
            for (d in dirs) {
                for (c in candidates) {
                    val f = File(d, c)
                    if (f.exists() && f.canExecute()) return f.absolutePath
                }
            }
        }
        
        // 2. Check common installation locations (macOS/Linux/Windows)
        val homeDir = System.getProperty("user.home")
        val commonPaths = buildList {
            // macOS Homebrew (Apple Silicon and Intel)
            add("/opt/homebrew/bin/node")
            add("/usr/local/bin/node")
            
            // Linux common paths
            add("/usr/bin/node")
            add("/usr/local/nodejs/bin/node")
            
            // nvm (Node Version Manager)
            if (homeDir != null) {
                val nvmDir = File(homeDir, ".nvm/versions/node")
                if (nvmDir.exists() && nvmDir.isDirectory) {
                    // Get the latest version or default
                    val defaultAlias = File(homeDir, ".nvm/alias/default")
                    val versionToUse = if (defaultAlias.exists()) {
                        defaultAlias.readText().trim()
                    } else {
                        // Fall back to latest installed version
                        nvmDir.listFiles()
                            ?.filter { it.isDirectory && it.name.startsWith("v") }
                            ?.maxByOrNull { it.name }
                            ?.name
                    }
                    if (versionToUse != null) {
                        add("$homeDir/.nvm/versions/node/$versionToUse/bin/node")
                    }
                    // Also add all nvm versions as fallback
                    nvmDir.listFiles()
                        ?.filter { it.isDirectory && it.name.startsWith("v") }
                        ?.sortedByDescending { it.name }
                        ?.forEach { add("${it.absolutePath}/bin/node") }
                }
                
                // fnm (Fast Node Manager)
                val fnmDir = File(homeDir, ".fnm/node-versions")
                if (fnmDir.exists() && fnmDir.isDirectory) {
                    fnmDir.listFiles()
                        ?.filter { it.isDirectory && it.name.startsWith("v") }
                        ?.sortedByDescending { it.name }
                        ?.forEach { add("${it.absolutePath}/installation/bin/node") }
                }
                
                // Volta
                add("$homeDir/.volta/bin/node")
                
                // asdf
                val asdfNodeDir = File(homeDir, ".asdf/installs/nodejs")
                if (asdfNodeDir.exists() && asdfNodeDir.isDirectory) {
                    asdfNodeDir.listFiles()
                        ?.filter { it.isDirectory }
                        ?.sortedByDescending { it.name }
                        ?.forEach { add("${it.absolutePath}/bin/node") }
                }
                
                // n (Node version manager)
                add("$homeDir/n/bin/node")
                add("/usr/local/n/versions/node")
            }
            
            // Windows common paths
            add("C:\\Program Files\\nodejs\\node.exe")
            add("C:\\Program Files (x86)\\nodejs\\node.exe")
        }
        
        for (path in commonPaths) {
            val f = File(path)
            if (f.exists() && f.canExecute()) return f.absolutePath
        }
        
        // 3. Try running 'which node' or 'where node' as a last resort
        return tryWhichCommand()
    }
    
    private fun tryWhichCommand(): String? {
        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val command = if (isWindows) arrayOf("cmd", "/c", "where", "node") else arrayOf("/bin/sh", "-c", "which node")
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
            val result = process.inputStream.bufferedReader().readLine()?.trim()
            process.waitFor()
            if (result != null && File(result).exists()) result else null
        } catch (e: Exception) {
            null
        }
    }
}
