package com.example.audioutil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class AudioResourcePackUtil {

    public interface AudioConverter {
        boolean convertToOgg(File mp3File, File oggFileToCreate) throws Exception;
    }

    private static class ConvertedSoundInfo {
        String originalFileName;
        String oggFileName;
        String soundEventName;
        String namespacedSoundPath;

        ConvertedSoundInfo(String originalFileName, String oggFileName, String soundEventName, String packNamespace) {
            this.originalFileName = originalFileName;
            this.oggFileName = oggFileName;
            this.soundEventName = soundEventName;
            this.namespacedSoundPath = packNamespace + ":" + soundEventName;
        }
    }

    public static class ResourcePackRequest {
        private final List<File> mp3Files;
        private final File outputZipFile;
        private final String packName;
        private final String packDescription;
        private final int packFormat;
        private final AudioConverter audioConverter;

        private ResourcePackRequest(Builder builder) {
            this.mp3Files = builder.mp3FilesProcessed;
            this.outputZipFile = builder.outputZipFile;
            this.packName = builder.packNameProcessed;
            this.packDescription = builder.packDescription;
            this.packFormat = builder.packFormat;
            this.audioConverter = builder.audioConverter;
        }

        public List<File> getMp3Files() { return mp3Files; }
        public File getOutputZipFile() { return outputZipFile; }
        public String getPackName() { return packName; }
        public String getPackDescription() { return packDescription; }
        public int getPackFormat() { return packFormat; }
        public AudioConverter getAudioConverter() { return audioConverter; }

        public static class Builder {
            private List<File> mp3FilesToProcess = new ArrayList<>();
            private File inputDirectory;
            private File outputZipFile;
            private String packNameInput;
            private String packDescription = "";
            private int packFormat;
            private AudioConverter audioConverter;
            
            private List<File> mp3FilesProcessed;
            private String packNameProcessed;

            public Builder addMp3File(File mp3File) {
                if (mp3File != null && mp3File.isFile() && mp3File.getName().toLowerCase().endsWith(".mp3")) {
                    this.mp3FilesToProcess.add(mp3File);
                }
                return this;
            }
            
            public Builder addMp3Files(Collection<File> mp3Files) {
                if (mp3Files != null) {
                    for (File file : mp3Files) {
                        addMp3File(file);
                    }
                }
                return this;
            }
    
            public Builder inputDirectory(File inputDirectory) {
                this.inputDirectory = inputDirectory;
                return this;
            }
            
            public Builder outputZipFile(File outputZipFile) {
                this.outputZipFile = outputZipFile;
                return this;
            }
    
            public Builder packName(String packName) {
                this.packNameInput = packName;
                return this;
            }
    
            public Builder packDescription(String packDescription) {
                this.packDescription = (packDescription == null) ? "" : packDescription;
                return this;
            }
    
            public Builder packFormat(int packFormat) {
                this.packFormat = packFormat;
                return this;
            }
    
            public Builder audioConverter(AudioConverter audioConverter) {
                this.audioConverter = audioConverter;
                return this;
            }
    
            public ResourcePackRequest build() {
                if (outputZipFile == null) throw new IllegalArgumentException("Output ZIP file must be specified.");
                if (packNameInput == null || packNameInput.trim().isEmpty()) throw new IllegalArgumentException("Pack name must be specified.");
                if (packFormat <= 0) throw new IllegalArgumentException("Pack format must be a positive integer.");
                if (audioConverter == null) throw new IllegalArgumentException("AudioConverter implementation must be provided.");
    
                this.packNameProcessed = packNameInput.toLowerCase().replaceAll("[^a-z0-9_.-]", "");
                if (this.packNameProcessed.isEmpty()) throw new IllegalArgumentException("Pack name became empty after sanitization. Please use valid characters (a-z, 0-9, _, ., -).");
    
                List<File> collectedMp3s = new ArrayList<>();
                for(File f : this.mp3FilesToProcess){ // Add explicitly added files first
                    if (!collectedMp3s.stream().anyMatch(existing -> existing.getAbsolutePath().equals(f.getAbsolutePath()))) {
                        collectedMp3s.add(f);
                    }
                }

                if (inputDirectory != null) {
                    if (!inputDirectory.isDirectory()) {
                        throw new IllegalArgumentException("Specified inputDirectory is not a directory: " + inputDirectory.getAbsolutePath());
                    }
                    File[] filesInDir = inputDirectory.listFiles((dir, name) -> name.toLowerCase().endsWith(".mp3"));
                    if (filesInDir != null) {
                        for (File file : filesInDir) {
                            if (!collectedMp3s.stream().anyMatch(f -> f.getAbsolutePath().equals(file.getAbsolutePath()))) {
                               collectedMp3s.add(file);
                            }
                        }
                    }
                }
                
                if (collectedMp3s.isEmpty()) throw new IllegalArgumentException("No valid MP3 files specified or found in input directory.");
                this.mp3FilesProcessed = collectedMp3s;
                
                return new ResourcePackRequest(this);
            }
        }
    }

    public static boolean createResourcePack(ResourcePackRequest request) throws IOException {
        File tempWorkingDir = Files.createTempDirectory("audiopackutil_").toFile();
        
        try {
            File assetsDir = new File(tempWorkingDir, "assets");
            File namespaceDir = new File(assetsDir, request.getPackName());
            File soundsDir = new File(namespaceDir, "sounds");
            if (!soundsDir.mkdirs()) {
                throw new IOException("Could not create directory structure: " + soundsDir.getAbsolutePath());
            }

            List<ConvertedSoundInfo> convertedSounds = new ArrayList<>();
            AudioConverter converter = request.getAudioConverter();

            for (File mp3File : request.getMp3Files()) {
                if (!mp3File.exists() || !mp3File.isFile()) {
                    System.err.println("WARN: MP3 file does not exist or is not a file: " + mp3File.getAbsolutePath());
                    continue;
                }

                String originalFileName = mp3File.getName();
                String baseName = originalFileName.substring(0, originalFileName.lastIndexOf('.'));
                String oggFileName = baseName + ".ogg";
                File oggFileTarget = new File(soundsDir, oggFileName);
                
                String soundEventName = baseName.toLowerCase().replaceAll("[^a-z0-9_]", "");
                if (soundEventName.isEmpty()) {
                    soundEventName = "sound_" + System.currentTimeMillis() + "_" + convertedSounds.size();
                }
                
                int counter = 0;
                String uniqueSoundEventName = soundEventName;
                while(convertedSounds.stream().anyMatch(cs -> cs.soundEventName.equals(uniqueSoundEventName))) {
                    counter++;
                    uniqueSoundEventName = soundEventName + "_" + counter;
                }
                soundEventName = uniqueSoundEventName;


                try {
                    if (converter.convertToOgg(mp3File, oggFileTarget)) {
                        convertedSounds.add(new ConvertedSoundInfo(originalFileName, oggFileName, soundEventName, request.getPackName()));
                    } else {
                        System.err.println("WARN: Conversion failed or skipped by converter for: " + mp3File.getName());
                    }
                } catch (Exception e) {
                     System.err.println("ERROR: Conversion error for " + mp3File.getName() + ": " + e.getMessage());
                     e.printStackTrace(System.err);
                }
            }

            if (convertedSounds.isEmpty()) {
                System.err.println("WARN: No sounds were successfully converted. Resource pack will not be created.");
                return false; 
            }

            createPackMcmeta(tempWorkingDir, request.getPackDescription(), request.getPackFormat());
            createSoundsJson(namespaceDir, request.getPackName(), convertedSounds);
            
            zipDirectory(tempWorkingDir, request.getOutputZipFile());
            return true;

        } finally {
            deleteDirectory(tempWorkingDir);
        }
    }

    private static void createPackMcmeta(File rootDir, String description, int packFormat) throws IOException {
        File packMcmetaFile = new File(rootDir, "pack.mcmeta");
        try (FileWriter writer = new FileWriter(packMcmetaFile)) {
            writer.write("{\n");
            writer.write("  \"pack\": {\n");
            writer.write("    \"pack_format\": " + packFormat + ",\n");
            writer.write("    \"description\": \"" + escapeJsonString(description) + "\"\n");
            writer.write("  }\n");
            writer.write("}\n");
        }
    }

    private static void createSoundsJson(File namespaceDir, String packNamespace, List<ConvertedSoundInfo> sounds) throws IOException {
        File soundsJsonFile = new File(namespaceDir, "sounds.json");
        try (FileWriter writer = new FileWriter(soundsJsonFile)) {
            writer.write("{\n");
            for (int i = 0; i < sounds.size(); i++) {
                ConvertedSoundInfo sound = sounds.get(i);
                String fullEventKey = "custom." + packNamespace + "." + sound.soundEventName; 
                
                writer.write("  \"" + escapeJsonString(fullEventKey) + "\": {\n");
                writer.write("    \"sounds\": [\n");
                writer.write("      \"" + escapeJsonString(packNamespace + ":" + sound.soundEventName) + "\"\n");
                writer.write("    ]\n");
                writer.write("  }");
                if (i < sounds.size() - 1) {
                    writer.write(",\n");
                } else {
                    writer.write("\n");
                }
            }
            writer.write("}\n");
        }
    }
    
    private static String escapeJsonString(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\b", "\\b")
                  .replace("\f", "\\f")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    private static void zipDirectory(File sourceDir, File zipFile) throws IOException {
        if (zipFile.exists()) {
            if(!zipFile.delete()){
                 throw new IOException("Could not delete existing zip file: " + zipFile.getAbsolutePath());
            }
        }
        Path sourcePath = sourceDir.toPath();
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            
            Files.walk(sourcePath)
                .filter(path -> !Files.isDirectory(path))
                .forEach(path -> {
                    String entryName = sourcePath.relativize(path).toString().replace(File.separatorChar, '/');
                    ZipEntry zipEntry = new ZipEntry(entryName);
                    try {
                        zos.putNextEntry(zipEntry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        System.err.println("ERROR: Could not add file to zip: " + path + " - " + e.getMessage());
                        throw new RuntimeException("Failed to add file to zip: " + path, e);
                    }
                });
        }
    }
    
    private static void deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        if (!directoryToBeDeleted.delete()) {
             System.err.println("WARN: Could not delete temporary directory/file: " + directoryToBeDeleted.getAbsolutePath());
        }
    }
}