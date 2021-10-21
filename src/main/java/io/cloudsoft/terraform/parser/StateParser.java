package io.cloudsoft.terraform.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.cloudsoft.terraform.TerraformConfiguration;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.cloudsoft.terraform.parser.PlanLogEntry.NO_CHANGES;

/**
 * Naive version. To be improved further.
 */
public class StateParser {
    public static final ImmutableList blankItems = ImmutableList.of("[]", "", "null", "\"\"", "{}", "[{}]");

    private static  Predicate<? super PlanLogEntry> changeSummaryPredicate = (Predicate<PlanLogEntry>) ple -> ple.type == PlanLogEntry.LType.CHANGE_SUMMARY;
    private static  Predicate<? super PlanLogEntry> outputsPredicate = (Predicate<PlanLogEntry>) ple -> ple.type == PlanLogEntry.LType.OUTPUTS;
    private static  Predicate<? super PlanLogEntry> plannedChangedPredicate = (Predicate<PlanLogEntry>) ple -> ple.type == PlanLogEntry.LType.PLANNED_CHANGE;
    private static  Predicate<? super PlanLogEntry> driftPredicate = (Predicate<PlanLogEntry>) ple -> ple.type == PlanLogEntry.LType.RESOURCE_DRIFT;
    private static  Predicate<? super PlanLogEntry> errorPredicate = (Predicate<PlanLogEntry>) ple -> ple.type == PlanLogEntry.LType.DIAGNOSTIC;

    public static Map<String, Object> parseResources(final String state){
        Map<String, Object> result  = new HashMap<>();
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            JsonNode root = objectMapper.readTree(state);

            if(root.isEmpty() || !root.isContainerNode() || root.get("terraform_version") == null) {
                throw new  IllegalArgumentException ("This is not a valid TF state!");
            }

            if(!root.has("values")) {
                throw new  IllegalArgumentException ("A valid deployment state should have a values node!");
            }
            if(!root.get("values").has("root_module")) {
                throw new  IllegalArgumentException ("A valid deployment state should have a root_module node!");
            }
            if(!root.get("values").get("root_module").has("resources")) {
                throw new  IllegalArgumentException ("A valid deployment state should have a resources node!");
            }

            JsonNode resourceNode = root.at("/values/root_module/resources");
            resourceNode.forEach(resource ->  {
                Map<String, Object>  resourceBody = new LinkedHashMap<>();

                if (resource.has("mode") && "managed".equals(resource.get("mode").asText())) {
                    result.put(resource.get("address").asText(), resourceBody);

                    resourceBody.put("resource.address", resource.get("address").asText());
                    resourceBody.put("resource.mode", resource.get("mode").asText());
                    resourceBody.put("resource.type", resource.get("type").asText());
                    resourceBody.put("resource.name", resource.get("name").asText());
                    resourceBody.put("resource.provider", resource.get("provider_name").asText());
                    if(resource.has("values")) {
                        Iterator<Map.Entry<String, JsonNode>>  it = resource.get("values").fields();
                        while(it.hasNext()) {
                            Map.Entry<String,JsonNode> value =  it.next();
                            if(value.getValue() != null && !blankItems.contains(value.getValue().toString())) {
                                resourceBody.put("value." + value.getKey(), value.getValue());
                                if (value.getKey().equalsIgnoreCase("instance_state")) {
                                    resourceBody.put("resource.status", value.getValue().asText());
                                }
                            }
                        }
                    }

                    if(resource.has("sensitive_values")) {
                        Iterator<Map.Entry<String, JsonNode>>  it = resource.get("sensitive_values").fields();
                        while(it.hasNext()) {
                            Map.Entry<String,JsonNode> value =  it.next();
                            if(value.getValue() != null && !blankItems.contains(value.getValue().toString())) {
                                resourceBody.put("sensitive.value." + value.getKey(), value.getValue() + "\n");
                            }
                        }
                    }
                }

            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse Terraform state!", e);
        }
        return result;
    }

    public static Map<String, Object> parsePlanLogEntries(final String planLogEntriesAsStr){
        String[] planLogEntries = planLogEntriesAsStr.split(System.lineSeparator());
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        List<PlanLogEntry> planLogs = Arrays.stream(planLogEntries).map(log -> {
            try {
                return objectMapper.readValue(log, PlanLogEntry.class);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
            return null;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();

        Optional<PlanLogEntry> changeSummaryLog = planLogs.stream().filter(changeSummaryPredicate).findFirst(); // it is not there when the config is broken
        if(changeSummaryLog.isPresent()) {
            if (NO_CHANGES.equals(changeSummaryLog.get().message)) {
                result.put("tf.plan.message", "No changes. Your infrastructure matches the configuration.");
                result.put("tf.plan.status", TerraformConfiguration.TerraformStatus.SYNC);
            } else {
                result.put("tf.plan.message", "Configuration and infrastructure do not match." + changeSummaryLog.get().message);
                result.put("tf.plan.status", TerraformConfiguration.TerraformStatus.DESYNCHRONIZED);
            }
        }

        planLogs.stream().filter(outputsPredicate).findFirst().ifPresent(ple -> {
            List<Map<String,Object>> outputs = new ArrayList<>();
            ple.outputs.forEach((oK, oV) -> {
                if (!"noop".equals(oV.get("action"))) {
                    outputs.add(ImmutableMap.of(
                            "output.addr", oK,
                            "output.action", oV.get("action").toString()
                    ));
                }
            });
            if(!outputs.isEmpty()) {
                result.put("tf.output.changes", outputs);
            }
        });

        if (planLogs.stream().anyMatch(plannedChangedPredicate)) {
            List<Map<String,Object>> resources = new ArrayList<>();
            planLogs.stream().filter(plannedChangedPredicate).forEach(ple -> {
                if (!"noop".equals(ple.change.get("action"))) {
                    resources.add(ImmutableMap.of(
                            "resource.addr", ((Map<String, String>) ple.change.get("resource")).get("addr"),
                            "resource.action", ple.change.get("action").toString()
                    ));
                }
            });
            if(!resources.isEmpty()) {
                result.put("tf.resource.changes", resources);
            }
        }


        if (planLogs.stream().anyMatch(driftPredicate)) {
            List<Map<String,Object>> resources = new ArrayList<>();
            planLogs.stream().filter(driftPredicate).forEach(ple -> {
                if (!"noop".equals(ple.change.get("action"))) {
                    resources.add(ImmutableMap.of(
                            "resource.addr", ((Map<String, String>) ple.change.get("resource")).get("addr"),
                            "resource.action", ple.change.get("action").toString()
                    ));
                }
            });
            if(!resources.isEmpty()) {
                result.put("tf.resource.changes", resources);
                result.put("tf.plan.status", TerraformConfiguration.TerraformStatus.DRIFT);
                result.put("tf.plan.message", "Drift Detected. Configuration and infrastructure do not match. Run apply to align infrastructure and configuration. Configurations made outside terraform will be lost if not added to the configuration." +  changeSummaryLog.get().message);
            }
        }

        if (planLogs.stream().anyMatch(errorPredicate)) {
            result.put("tf.plan.message", "Something went wrong. Check your configuration.");
            result.put("tf.plan.status", TerraformConfiguration.TerraformStatus.ERROR);
            StringBuilder sb = new StringBuilder();
            planLogs.stream().filter(ple -> ple.type == PlanLogEntry.LType.DIAGNOSTIC).forEach(ple ->
                sb.append(ple.diagnostic.get("summary")).append(":").append("detail").append("\n")
            );
            result.put("tf.errors",  sb);
        }
        return result;
    }
}