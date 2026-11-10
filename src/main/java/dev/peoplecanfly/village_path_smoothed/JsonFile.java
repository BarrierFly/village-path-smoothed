/*
 * This file is part of the Village Path Smoothed project, licensed under the
 * WTFPL v2 (Do What The Fuck You Want To Public License).
 */

package dev.peoplecanfly.village_path_smoothed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Small JSON file helpers shared by the global config and the per-world switch.
 *
 * <p>2026-11-10 audit H-3: both files used {@code Files.writeString}, which
 * truncates the target first. A process killed between truncation and the last
 * byte left a half-written file, and the next load then replaced a recoverable
 * file with defaults. Writing a sibling temp file and moving it into place makes
 * the replacement atomic on one volume (Windows {@code MoveFileEx} and POSIX
 * {@code rename(2)}), and {@link #quarantine} keeps an unreadable file for
 * inspection instead of overwriting it.
 */
public final class JsonFile {
	private JsonFile() {
	}

	/**
	 * Writes {@code json} to {@code path} so a crash can never leave a truncated
	 * file behind. The temp file lives in the same directory, so the move is a
	 * rename on one volume.
	 */
	public static void writeAtomically(Path path, String json) throws IOException {
		Path dir = path.getParent();
		if (dir != null) {
			Files.createDirectories(dir);
		}
		Path tmp = Files.createTempFile(dir, path.getFileName().toString(), ".tmp");
		try {
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			try {
				Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * Moves an unreadable file aside as {@code <name>.corrupt-<millis>} so the
	 * user's original bytes survive a rewrite with defaults. Returns the new path,
	 * or the original when the move failed (the caller still has to continue).
	 */
	public static Path quarantine(Path path) {
		Path moved = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis());
		try {
			Files.move(path, moved, StandardCopyOption.REPLACE_EXISTING);
			return moved;
		} catch (IOException e) {
			return path;
		}
	}
}
