package com.lifebuoysystem.entity;


/**
 * @author ZKQ
 */

import lombok.Data;

@Data
public class TargetInfo {

    private Integer index;

    private String label;

    private Double score;

    private Integer centerX;

    private Integer centerY;
}