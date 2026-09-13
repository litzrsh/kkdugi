package kkdugi.core.models;

import java.io.Serial;
import java.util.List;

import kkdugi.core.Constants;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class Menu implements Tree<Menu> {

    @Serial
    private static final long serialVersionUID = Constants.SERIAL_ID;

    private String id;
    private String parentId;
    private String name;
    private String description;
    private String pragma;
    private String icon;
    private int sort;
    private List<Menu> children;
}
