package kkdugi.core.util;

import java.util.List;

import kkdugi.core.models.Tree;

public abstract class TreeUtils {

    public static <T extends Tree<T>> List<T> convert(List<T> array) {
        List<T> roots = array.stream()
                .filter((e) -> CommonUtils.isEmpty(e.getParentId())).sorted().toList();
        for (T root : roots) {
            setChildren(root, array);
        }
        return roots;
    }

    private static <T extends Tree<T>> void setChildren(T root, List<T> array) {
        List<T> children = array.stream()
                .filter((e) -> root.getId().equals(e.getParentId())).sorted().toList();
        if (CommonUtils.isNotEmpty(children)) {
            root.setChildren(children);
            for (T node : children) {
                setChildren(node, array);
            }
        } else {
            root.setChildren(null);
        }
    }
}
