package kkdugi.core.util;

import java.util.List;

import kkdugi.core.models.Tree;

public abstract class TreeUtils {

    public static <T extends Tree<T>> List<T> convert(List<T> array) {
        if (CommonUtils.isEmpty(array)) return List.of();
        List<T> roots = array.stream()
                .filter(i -> i.getId() == null)
                .sorted().toList();
        for (T root : roots) {
            setChildren(root, array);
        }
        return roots;
    }

    private static <T extends Tree<T>> void setChildren(T root, List<T> array) {
        List<T> children = array.stream()
                .filter(i -> root.getId().equals(i.getParentId()))
                .sorted().toList();
        if (CommonUtils.isNotEmpty(children)) {
            root.setChildren(children);
            for (T child : children) {
                setChildren(child, array);
            }
        }
    }
}
