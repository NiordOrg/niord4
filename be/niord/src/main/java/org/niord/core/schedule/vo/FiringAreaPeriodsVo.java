package org.niord.core.schedule.vo;

import org.niord.core.area.vo.SystemAreaVo;
import org.niord.model.IJsonSerializable;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines a firing area along with its firing periods
 */
@SuppressWarnings("unused")
public class FiringAreaPeriodsVo implements IJsonSerializable {

    SystemAreaVo area;

    /**
     * The area's periods of the day. A body read from JSON leaves it null when the field is missing, so an
     * update can tell "no periods this day" (an empty list) from a body that omits the field; an area
     * listed by the backend starts with an empty list.
     */
    List<FiringPeriodVo> firingPeriods;

    /**
     * In an update only, optional: the ids of the area's periods of the day that the client loaded before its
     * changes. An update that names them deletes only those of them it leaves out of {@link #firingPeriods}, never
     * a period it did not load, so a period another client added in the meantime is kept. An update without them
     * replaces the day's periods: every stored period of the day that the body leaves out is deleted.
     */
    List<Integer> loadedPeriodIds;

    /** Constructor **/
    public FiringAreaPeriodsVo() {
    }

    /** Constructor **/
    public FiringAreaPeriodsVo(SystemAreaVo area) {
        this.area = area;
        this.firingPeriods = new ArrayList<>();
    }

    /*************************/
    /** Getters and Setters **/
    /*************************/

    public SystemAreaVo getArea() {
        return area;
    }

    public void setArea(SystemAreaVo area) {
        this.area = area;
    }

    public List<FiringPeriodVo> getFiringPeriods() {
        return firingPeriods;
    }

    public void setFiringPeriods(List<FiringPeriodVo> firingPeriods) {
        this.firingPeriods = firingPeriods;
    }

    public List<Integer> getLoadedPeriodIds() {
        return loadedPeriodIds;
    }

    public void setLoadedPeriodIds(List<Integer> loadedPeriodIds) {
        this.loadedPeriodIds = loadedPeriodIds;
    }
}
