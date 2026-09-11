// SPDX-License-Identifier: Apache-2.0
package demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.apache.commons.text.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Plain CLI fixture: a handful of common libraries with no framework, packaged three ways. */
@Command(name = "plain-cli", mixinStandardHelpOptions = true)
public final class Cli implements Callable<Integer> {
    private static final Logger LOG = LoggerFactory.getLogger(Cli.class);

    @Parameters(defaultValue = "hello world")
    String text = "";

    @Override
    public Integer call() throws Exception {
        List<String> words = Splitter.on(' ').splitToList(WordUtils.capitalize(text));
        LOG.info("{}", new ObjectMapper().writeValueAsString(Map.of("words", words)));
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Cli()).execute(args));
    }
}
