package io.cloudsoft.terraform.entity;

import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.stock.BasicEntityImpl;

import java.util.Map;

public class ManagedResourceImpl extends BasicEntityImpl implements  ManagedResource {

    @Override
    public void init() {
        super.init();
        connectSensors();
    }

    protected void connectSensors() {
        Map<String, Object> resourceDetails = this.getConfig(StartableManagedResource.STATE_CONTENTS);
        resourceDetails.forEach((k,v) -> sensors().set(Sensors.newSensor(Object.class, "tf." + k), v.toString()));
        if(!resourceDetails.containsKey("resource.status")) {
            sensors().set(RESOURCE_STATUS, "ok"); // the provider doesn't provide any property to let us know the state of the resource
        }
        this.setDisplayName(getConfig(StartableManagedResource.ADDRESS));
        updateResourceState();
    }

    @Override
    public boolean refreshSensors(Map<String, Object> resource) {
        resource.forEach((k, v) -> {
            if (!sensors().get(Sensors.newSensor(Object.class, "tf." + k)).equals(v)){
                sensors().set(Sensors.newSensor(Object.class, "tf." + k), v.toString());
            }
        });
        updateResourceState();
        return true;
    }

    @Override
    public void updateResourceState() {
        final String resourceStatus = sensors().get(RESOURCE_STATUS);
        if(resourceStatus.equals("changed")) {
            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.ON_FIRE);
            ServiceStateLogic.updateMapSensorEntry(this, Attributes.SERVICE_PROBLEMS,
                    "TF-ASYNC", "Resource changed outside terraform.");
        } else {
            sensors().set(Attributes.SERVICE_STATE_ACTUAL, Lifecycle.CREATED);
        }
    }
}
