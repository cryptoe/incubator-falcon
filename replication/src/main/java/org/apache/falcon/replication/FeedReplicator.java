/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.falcon.replication;

import org.apache.commons.cli.*;
import org.apache.commons.lang.StringUtils;
import org.apache.falcon.entity.EntityUtil;
import org.apache.falcon.entity.Storage;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.tools.DistCp;
import org.apache.hadoop.tools.DistCpOptions;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A tool for feed replication that uses DistCp tool to replicate.
 */
public class FeedReplicator extends Configured implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(FeedReplicator.class);

    public static void main(String[] args) throws Exception {
        ToolRunner.run(new Configuration(), new FeedReplicator(), args);
    }

    @Override
    public int run(String[] args) throws Exception {
        CommandLine cmd = getCommand(args);
        DistCpOptions options = getDistCpOptions(cmd);

        Configuration conf = this.getConf();
        // inject wf configs
        Path confPath = new Path("file:///"
                + System.getProperty("oozie.action.conf.xml"));

        LOG.info("{} found conf ? {}", confPath, confPath.getFileSystem(conf).exists(confPath));
        conf.addResource(confPath);

        String falconFeedStorageType = cmd.getOptionValue("falconFeedStorageType").trim();
        Storage.TYPE feedStorageType = Storage.TYPE.valueOf(falconFeedStorageType);

        DistCp distCp = (feedStorageType == Storage.TYPE.FILESYSTEM)
                ? new CustomReplicator(conf, options)
                : new DistCp(conf, options);
        LOG.info("Started DistCp");
        distCp.execute();

        if (feedStorageType == Storage.TYPE.FILESYSTEM) {
            executePostProcessing(options);  // this only applies for FileSystem Storage.
        }

        LOG.info("Completed DistCp");
        return 0;
    }

    protected CommandLine getCommand(String[] args) throws ParseException {
        Options options = new Options();
        Option opt = new Option("maxMaps", true,
                "max number of maps to use for this copy");
        opt.setRequired(true);
        options.addOption(opt);

        opt = new Option("mapBandwidthKB", true,
                "bandwidth per map (in KB) to use for this copy");
        opt.setRequired(true);
        options.addOption(opt);

        opt = new Option("sourcePaths", true,
                "comma separtated list of source paths to be copied");
        opt.setRequired(true);
        options.addOption(opt);

        opt = new Option("targetPath", true, "target path");
        opt.setRequired(true);
        options.addOption(opt);

        opt = new Option("falconFeedStorageType", true, "feed storage type");
        opt.setRequired(true);
        options.addOption(opt);

        return new GnuParser().parse(options, args);
    }

    protected DistCpOptions getDistCpOptions(CommandLine cmd) {
        String[] paths = cmd.getOptionValue("sourcePaths").trim().split(",");
        List<Path> srcPaths = getPaths(paths);
        String trgPath = cmd.getOptionValue("targetPath").trim();

        DistCpOptions distcpOptions = new DistCpOptions(srcPaths, new Path(trgPath));
        distcpOptions.setSyncFolder(true);
        distcpOptions.setBlocking(true);
        distcpOptions.setMaxMaps(Integer.valueOf(cmd.getOptionValue("maxMaps")));
        distcpOptions.setMapBandwidthKB(Integer.valueOf(cmd.getOptionValue("mapBandwidthKB")));

        return distcpOptions;
    }

    private List<Path> getPaths(String[] paths) {
        List<Path> listPaths = new ArrayList<Path>();
        for (String path : paths) {
            listPaths.add(new Path(path));
        }
        return listPaths;
    }

    private void executePostProcessing(DistCpOptions options) throws IOException {
        Path targetPath = options.getTargetPath();
        FileSystem fs = targetPath.getFileSystem(getConf());
        List<Path> inPaths = options.getSourcePaths();
        assert inPaths.size() == 1 : "Source paths more than 1 can't be handled";

        Path sourcePath = inPaths.get(0);
        Path includePath = new Path(getConf().get("falcon.include.path"));
        assert includePath.toString().substring(0, sourcePath.toString().length()).
                equals(sourcePath.toString()) : "Source path is not a subset of include path";

        String relativePath = includePath.toString().substring(sourcePath.toString().length());
        String fixedPath = getFixedPath(relativePath);

        fixedPath = StringUtils.stripStart(fixedPath, "/");
        Path finalOutputPath;
        if (StringUtils.isNotEmpty(fixedPath)) {
            finalOutputPath = new Path(targetPath, fixedPath);
        } else {
            finalOutputPath = targetPath;
        }

        FileStatus[] files = fs.globStatus(finalOutputPath);
        if (files != null) {
            for (FileStatus file : files) {
                fs.create(new Path(file.getPath(), EntityUtil.SUCCEEDED_FILE_NAME)).close();
                LOG.info("Created {}", new Path(file.getPath(), EntityUtil.SUCCEEDED_FILE_NAME));
            }
        } else {
            // As distcp is not copying empty directories we are creating  _SUCCESS file here
            fs.create(new Path(finalOutputPath, EntityUtil.SUCCEEDED_FILE_NAME)).close();
            LOG.info("No files present in path: {}", finalOutputPath);
        }
    }

    private String getFixedPath(String relativePath) throws IOException {
        String[] patterns = relativePath.split("/");
        int part = patterns.length - 1;
        for (int index = patterns.length - 1; index >= 0; index--) {
            String pattern = patterns[index];
            if (pattern.isEmpty()) {
                continue;
            }
            Pattern r = FilteredCopyListing.getRegEx(pattern);
            if (!r.toString().equals("(" + pattern + "/)|(" + pattern + "$)")) {
                continue;
            }
            part = index;
            break;
        }
        StringBuilder resultBuffer = new StringBuilder();
        for (int index = 0; index <= part; index++) {
            resultBuffer.append(patterns[index]).append("/");
        }
        String result = resultBuffer.toString();
        return result.substring(0, result.lastIndexOf('/'));
    }
}
