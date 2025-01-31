/*
 * Copyright 2020-2022, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.seqera.wave.plugin

import nextflow.cli.PluginExecAware
import nextflow.plugin.BasePlugin
import org.pf4j.PluginWrapper
/**
 * Wave plugin entrypoint
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class WavePlugin extends BasePlugin implements PluginExecAware {

    @Delegate
    private WaveCmdCli cli

    WavePlugin(PluginWrapper wrapper) {
        super(wrapper)
        this.cli = new WaveCmdCli()
    }

}
