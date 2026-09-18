package kkdugi.core.models;

import java.util.List;

public interface Tree<T> extends Comparable<Tree<T>> {

    String getId();

    String getParentId();

    int getSort();

    void setChildren(List<T> children);

    @Override
    default int compareTo(Tree<T> o) {
        return getSort() - o.getSort();
    }
}
