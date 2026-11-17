/*
 * Copyright 2026 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.niord.core.script.vo;

import org.niord.core.script.ScriptResource;
import org.niord.model.IJsonSerializable;

import java.util.ArrayList;
import java.util.List;

/**
 * What a Script resources import file would do, which the import dialog shows before anything is written
 * (docs/code-data-config-plan.md §6.4): the paths it adds, the paths it would overwrite with the row's content and
 * the file's, the paths whose content is unchanged, and the files it refuses with their reasons. Each list is sorted
 * by path.
 */
@SuppressWarnings("unused")
public class ScriptResourceImportPreviewVo implements IJsonSerializable {

    List<Add> add = new ArrayList<>();
    List<Overwrite> overwrite = new ArrayList<>();
    List<String> unchanged = new ArrayList<>();
    List<Refused> refused = new ArrayList<>();

    /** A path the database lacks; {@code listed} when the missing-path report names it */
    public static class Add implements IJsonSerializable {
        String path;
        ScriptResource.Type type;
        String content;
        boolean listed;

        public Add() {
        }

        public Add(String path, ScriptResource.Type type, String content, boolean listed) {
            this.path = path;
            this.type = type;
            this.content = content;
            this.listed = listed;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public ScriptResource.Type getType() {
            return type;
        }

        public void setType(ScriptResource.Type type) {
            this.type = type;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public boolean isListed() {
            return listed;
        }

        public void setListed(boolean listed) {
            this.listed = listed;
        }
    }

    /**
     * A path the database has with other content: the row's ({@code current}) and the file's ({@code incoming}), and
     * {@code basedOn}, the fingerprint of the row's content ({@code sha256:<64 hex>}), which a tick sends back so
     * that the job keeps a row changed since the preview
     */
    public static class Overwrite implements IJsonSerializable {
        String path;
        ScriptResource.Type type;
        String current;
        String incoming;
        String basedOn;

        public Overwrite() {
        }

        public Overwrite(String path, ScriptResource.Type type, String current, String incoming, String basedOn) {
            this.path = path;
            this.type = type;
            this.current = current;
            this.incoming = incoming;
            this.basedOn = basedOn;
        }

        public String getBasedOn() {
            return basedOn;
        }

        public void setBasedOn(String basedOn) {
            this.basedOn = basedOn;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public ScriptResource.Type getType() {
            return type;
        }

        public void setType(ScriptResource.Type type) {
            this.type = type;
        }

        public String getCurrent() {
            return current;
        }

        public void setCurrent(String current) {
            this.current = current;
        }

        public String getIncoming() {
            return incoming;
        }

        public void setIncoming(String incoming) {
            this.incoming = incoming;
        }
    }

    /** A file the import refuses, and why */
    public static class Refused implements IJsonSerializable {
        String path;
        String reason;

        public Refused() {
        }

        public Refused(String path, String reason) {
            this.path = path;
            this.reason = reason;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    /*************************/
    /** Getters and Setters **/
    /*************************/

    public List<Add> getAdd() {
        return add;
    }

    public void setAdd(List<Add> add) {
        this.add = add;
    }

    public List<Overwrite> getOverwrite() {
        return overwrite;
    }

    public void setOverwrite(List<Overwrite> overwrite) {
        this.overwrite = overwrite;
    }

    public List<String> getUnchanged() {
        return unchanged;
    }

    public void setUnchanged(List<String> unchanged) {
        this.unchanged = unchanged;
    }

    public List<Refused> getRefused() {
        return refused;
    }

    public void setRefused(List<Refused> refused) {
        this.refused = refused;
    }
}
