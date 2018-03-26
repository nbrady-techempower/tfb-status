package tfb.status.handler;

import static com.google.common.net.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN;
import static com.google.common.net.MediaType.HTML_UTF_8;
import static io.undertow.util.Headers.CONTENT_TYPE;
import static io.undertow.util.Methods.GET;
import static io.undertow.util.StatusCodes.INTERNAL_SERVER_ERROR;
import static io.undertow.util.StatusCodes.NOT_FOUND;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.io.MoreFiles;
import com.google.common.net.MediaType;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.DisableCacheHandler;
import io.undertow.server.handlers.SetHeaderHandler;
import io.undertow.util.MimeMappings;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tfb.status.config.FileStoreConfig;
import tfb.status.service.MustacheRenderer;
import tfb.status.undertow.extensions.DefaultToUtf8Handler;
import tfb.status.undertow.extensions.MethodHandler;
import tfb.status.util.ZipFiles;
import tfb.status.view.DirectoryListingView;
import tfb.status.view.DirectoryListingView.FileView;

/**
 * Handles requests to extract files from within results.zip files.
 */
@Singleton
public final class UnzipResultsHandler implements HttpHandler {
  private final HttpHandler delegate;

  @Inject
  public UnzipResultsHandler(FileStoreConfig fileStoreConfig,
                             MustacheRenderer mustacheRenderer) {

    HttpHandler handler = new CoreHandler(fileStoreConfig, mustacheRenderer);

    handler = new DefaultToUtf8Handler(handler);
    handler = new MethodHandler().addMethod(GET, handler);
    handler = new DisableCacheHandler(handler);
    handler = new SetHeaderHandler(handler, ACCESS_CONTROL_ALLOW_ORIGIN, "*");

    delegate = handler;
  }

  @Override
  public void handleRequest(HttpServerExchange exchange) throws Exception {
    delegate.handleRequest(exchange);
  }

  private static final class CoreHandler implements HttpHandler {
    private final Path resultsDirectory;
    private final MustacheRenderer mustacheRenderer;
    private final Logger logger = LoggerFactory.getLogger(getClass());

    CoreHandler(FileStoreConfig fileStoreConfig,
                MustacheRenderer mustacheRenderer) {
      this.resultsDirectory = Paths.get(fileStoreConfig.resultsDirectory);
      this.mustacheRenderer = Objects.requireNonNull(mustacheRenderer);
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {

      if (exchange.getRelativePath().isEmpty()) {
        exchange.setStatusCode(NOT_FOUND);
        return;
      }

      String relativePath = exchange.getRelativePath()
                                    .substring(1); // omit leading slash

      Path requestedFile;
      try {
        requestedFile = resultsDirectory.resolve(relativePath);
      } catch (InvalidPathException ignored) {
        exchange.setStatusCode(NOT_FOUND);
        return;
      }

      if (!requestedFile.equals(requestedFile.normalize())
          || !requestedFile.startsWith(resultsDirectory)) {
        exchange.setStatusCode(NOT_FOUND);
        return;
      }

      Path zipFileAndEntry = resultsDirectory.relativize(requestedFile);

      Path zipFile = resultsDirectory.resolve(zipFileAndEntry.getName(0));

      if (!Files.isRegularFile(zipFile)
          || !MoreFiles.getFileExtension(zipFile).equals("zip")) {
        exchange.setStatusCode(NOT_FOUND);
        return;
      }

      String entrySubPath =
          (zipFileAndEntry.getNameCount() == 1)
              ? ""
              : Joiner.on('/').join(zipFileAndEntry.subpath(1, zipFileAndEntry.getNameCount()));

      ZipFiles.findZipEntry(
          /* zipFile= */ zipFile,
          /* entryPath= */ entrySubPath,
          /* ifPresent= */
          zipEntry -> {

            if (Files.isRegularFile(zipEntry)) {
              MediaType mediaType = guessMediaType(zipEntry);

              if (mediaType != null)
                exchange.getResponseHeaders().put(CONTENT_TYPE,
                                                  mediaType.toString());

              try (InputStream in = Files.newInputStream(zipEntry)) {
                in.transferTo(exchange.getOutputStream());
              }
            }

            else if (Files.isDirectory(zipEntry)) {
              List<FileView> breadcrumbs = new ArrayList<>();
              for (int i = 1; i <= zipFileAndEntry.getNameCount(); i++) {
                Path directory = zipFileAndEntry.subpath(0, i);
                breadcrumbs.add(
                    new FileView(
                        /* fileName= */ directory.getFileName().toString(),
                        /* fullPath= */ Joiner.on('/').join(directory),
                        /* size= */ null,
                        /* isDirectory= */ true,
                        /* isSelected= */ i == zipFileAndEntry.getNameCount()));
              }

              List<FileView> children = new ArrayList<>();

              try (DirectoryStream<Path> files = Files.newDirectoryStream(zipEntry)) {
                for (Path file : files) {
                  BasicFileAttributes attributes =
                      Files.readAttributes(file, BasicFileAttributes.class);

                  children.add(
                      new FileView(
                          /* fileName= */ file.getFileName().toString(),
                          /* fullPath= */ zipFile.getFileName().toString() + "/" + Joiner.on('/').join(file),
                          /* size= */ attributes.isRegularFile()
                                              ? fileSizeToString(attributes.size(), /* si= */ true)
                                              : null,
                          /* isDirectory= */ attributes.isDirectory(),
                          /* isSelected= */ false));
                }
              }

              children.sort(comparing((FileView file) -> !file.isDirectory)
                                 .thenComparing(file -> file.fileName,
                                                String.CASE_INSENSITIVE_ORDER));

              DirectoryListingView directoryView =
                  new DirectoryListingView(
                      /* breadcrumbs= */ ImmutableList.copyOf(breadcrumbs),
                      /* children= */ ImmutableList.copyOf(children));

              String html = mustacheRenderer.render("directory-listing.mustache", directoryView);
              exchange.getResponseHeaders().put(CONTENT_TYPE, HTML_UTF_8.toString());
              exchange.getResponseSender().send(html, UTF_8);
            }

            else {
              logger.warn(
                  "Cannot unzip a zip entry that is neither a file nor a "
                      + "directory, zip file = {}, zip entry = {}",
                  zipFile,
                  zipEntry);

              exchange.setStatusCode(INTERNAL_SERVER_ERROR);
            }

          },
          /* ifAbsent= */ () -> exchange.setStatusCode(NOT_FOUND));
    }

    /**
     * Guesses the media type of the given file.  Returns {@code null} if there
     * is not a good guess.
     */
    @Nullable
    static MediaType guessMediaType(Path file) {
      String extension = MoreFiles.getFileExtension(file);

      String mediaTypeString =
          MimeMappings.DEFAULT_MIME_MAPPINGS.get(extension);

      if (mediaTypeString == null)
        return null;

      return MediaType.parse(mediaTypeString);
    }

    // https://stackoverflow.com/a/3758880/359008
    static String fileSizeToString(long bytes, boolean si) {
      int unit = si ? 1000 : 1024;
      if (bytes < unit) return bytes + " B";
      int exp = (int) (Math.log(bytes) / Math.log(unit));
      String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp-1) + (si ? "" : "i");
      return String.format(Locale.ROOT, "%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }
  }
}
