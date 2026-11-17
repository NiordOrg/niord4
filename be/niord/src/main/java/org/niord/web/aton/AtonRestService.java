/*
 * Copyright 2016 Danish Maritime Authority.
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
package org.niord.web.aton;

import org.niord.core.aton.*;
import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.web.NiordWebException;
import org.niord.model.IJsonSerializable;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.PermitAll;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST interface for accessing AtoNs.
 */
@RestController
@RequestMapping("/rest/atons")
@PermitAll
public class AtonRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AtonRestService.class);

    private final AtonService atonService;

    private final AtonDefaultsService atonDefaultsService;

    AtonRestService(AtonService atonService, AtonDefaultsService atonDefaultsService) {
        this.atonService = atonService;
        this.atonDefaultsService = atonDefaultsService;
    }

    /** Returns the AtoN with the given comma-separated IDs */
    @GetMapping(value = "/aton/{atonUids}", produces = "application/json;charset=UTF-8")
    public List<AtonNodeVo> getAtons(@PathVariable("atonUids") String atonUids) {
        return atonService.findByAtonUids(atonUids.split(",")).stream()
                .map(AtonNode::toVo)
                .collect(Collectors.toList());
    }

    /** Returns the AtoNs within the given bounds */
    @GetMapping(value = "/search-name", produces = "application/json;charset=UTF-8")
    public List<AtonNodeVo> searchAtons(
            @RequestParam(name = "name", defaultValue = "") String name,
            @RequestParam(name = "maxAtonNo", defaultValue = "1000") int maxAtonNo
    ) {
        AtonSearchParams param = new AtonSearchParams();
        param.name(name);
        param.maxSize(maxAtonNo);

        return atonService.search(param)
                .map(AtonNode::toVo)
                .getData();
    }

    /** Returns the AtoNs within the given bounds */
    @GetMapping(value = "/search", produces = "application/json;charset=UTF-8")
    public PagedSearchResultVo<AtonNodeVo> search(
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "chart", required = false) Set<String> charts,
            @RequestParam(name = "area", required = false) Set<Integer> areas,
            @RequestParam(name = "minLat", required = false) Double minLat,
            @RequestParam(name = "minLon", required = false) Double minLon,
            @RequestParam(name = "maxLat", required = false) Double maxLat,
            @RequestParam(name = "maxLon", required = false) Double maxLon,
            @RequestParam(name = "maxAtonNo", required = false) Integer maxAtonNo,
            @RequestParam(name = "emptyOnOverflow", defaultValue = "false") boolean emptyOnOverflow
    ) {

        AtonSearchParams param = new AtonSearchParams()
            .name(name)
            .extent(minLat, minLon, maxLat, maxLon)
            .chartNumbers(charts)
            .areaIds(areas)
            .emptyOnOverflow(emptyOnOverflow);
        param.maxSize(maxAtonNo);

        PagedSearchResultVo<AtonNode> atons = atonService.search(param);

        return atons.map(AtonNode::toVo);
    }

    /**
     * Returns the name of all node types where the name matches the parameter
     *
     * @param name the substring match
     * @return the name of all node types where the name matches the parameter
     */
    @GetMapping(value = "/defaults/node-types", produces = "application/json;charset=UTF-8")
    public List<String> getNodeTypeNames(@RequestParam(name = "name", required = false) String name) {
        return atonDefaultsService.getNodeTypeNames(name);
    }

    /**
     * Merges the given AtoN with the tags of the node types with the given names
     *
     * @param atonNodeTypeParam the AtoN and node type names
     * @return the updated AtoN
     */
    @PostMapping(value = "/defaults/merge-with-node-types",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public AtonNodeVo mergeAtonWithNodeTypes(@RequestBody AtonNodeTypeParam atonNodeTypeParam) throws Exception {
        if (atonNodeTypeParam.getAton() == null) {
            throw new NiordWebException("No AtoN to merge", 400);
        }
        AtonNode aton = new AtonNode(atonNodeTypeParam.getAton());
        // No node type names is the AtoN unchanged
        if (atonNodeTypeParam.getNodeTypeNames() != null) {
            atonNodeTypeParam.getNodeTypeNames()
                    .forEach(nt -> atonDefaultsService.mergeAtonWithNodeTypes(aton, nt));
        }
        return aton.toVo();
    }

    /**
     * Creates an auto-complete list for OSM tag keys, based on the current AtoN and key
     *
     * @param key the currently typed key
     * @param aton the current AtoN
     * @return the auto-complete list
     */
    @PostMapping(value = "/defaults/auto-complete-key",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public List<String> autoCompleteKey(
            @RequestParam(name = "key", required = false) String key,
            @RequestBody AtonNodeVo aton) throws Exception {

        return atonDefaultsService.computeKeysForAton(new AtonNode(aton), key, 20);
    }

    /**
     * Creates an auto-complete list for OSM tag values, based on the current AtoN, key and value
     *
     * @param key the current key
     * @param value the currently typed value
     * @param aton the current AtoN
     * @return the auto-complete list
     */
    @PostMapping(value = "/defaults/auto-complete-value",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public List<String> autoCompleteValue(
            @RequestParam(name = "key", required = false) String key,
            @RequestParam(name = "value", required = false) String value,
            @RequestBody AtonNodeVo aton) throws Exception {

        return atonDefaultsService.getValuesForAtonAndKey(new AtonNode(aton), key, value, 20);
    }

    /*************************/
    /** Helper classes      **/
    /*************************/

    /** Encapsulates the parameters uses for merging an AtoN with the node type tags */
    @SuppressWarnings("unused")
    public static class AtonNodeTypeParam implements IJsonSerializable {
        AtonNodeVo aton;
        List<String> nodeTypeNames = new ArrayList<>();

        public AtonNodeVo getAton() {
            return aton;
        }

        public void setAton(AtonNodeVo aton) {
            this.aton = aton;
        }

        public List<String> getNodeTypeNames() {
            return nodeTypeNames;
        }

        public void setNodeTypeNames(List<String> nodeTypeNames) {
            this.nodeTypeNames = nodeTypeNames;
        }
    }
}
